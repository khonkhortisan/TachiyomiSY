package eu.kanade.tachiyomi.data.library

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.toMangaInfo
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.library.LibraryUpdateService.Companion.start
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.data.preference.PreferenceValues.GroupLibraryMode
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.toMangaInfo
import eu.kanade.tachiyomi.source.model.toSChapter
import eu.kanade.tachiyomi.source.model.toSManga
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.chapter.NoChaptersException
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.prepUpdateCover
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.acquireWakeLock
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isServiceRunning
import eu.kanade.tachiyomi.util.system.logcat
import exh.log.xLogE
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.metadata.metadata.base.insertFlatMetadataAsync
import exh.source.LIBRARY_UPDATE_EXCLUDED_SOURCES
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import exh.source.isMdBasedSource
import exh.source.mangaDexSourceIds
import exh.util.executeOnIO
import exh.util.nullIfBlank
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * This class will take care of updating the chapters of the manga from the library. It can be
 * started calling the [start] method. If it's already running, it won't do anything.
 * While the library is updating, a [PowerManager.WakeLock] will be held until the update is
 * completed, preventing the device from going to sleep mode. A notification will display the
 * progress of the update, and if case of an unexpected error, this service will be silently
 * destroyed.
 */
class LibraryUpdateService(
    val db: DatabaseHelper = Injekt.get(),
    val sourceManager: SourceManager = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    val downloadManager: DownloadManager = Injekt.get(),
    val trackManager: TrackManager = Injekt.get(),
    val coverCache: CoverCache = Injekt.get(),
) : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notifier: LibraryUpdateNotifier
    private var ioScope: CoroutineScope? = null

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()
    private var updateJob: Job? = null

    /**
     * Defines what should be updated within a service execution.
     */
    enum class Target {
        CHAPTERS, // Manga chapters
        COVERS, // Manga covers
        TRACKING, // Tracking metadata

        // SY -->
        SYNC_FOLLOWS, // MangaDex specific, pull mangadex manga in reading, rereading

        PUSH_FAVORITES // MangaDex specific, push mangadex manga to mangadex
        // SY <--
    }

    companion object {

        private var instance: LibraryUpdateService? = null

        /**
         * Key for category to update.
         */
        const val KEY_CATEGORY = "category"

        /**
         * Key that defines what should be updated.
         */
        const val KEY_TARGET = "target"

        // SY -->
        /**
         * Key for group to update.
         */
        const val KEY_GROUP = "group"
        const val KEY_GROUP_EXTRA = "group_extra"
        // SY <--

        /**
         * Returns the status of the service.
         *
         * @param context the application context.
         * @return true if the service is running, false otherwise.
         */
        fun isRunning(context: Context): Boolean {
            return context.isServiceRunning(LibraryUpdateService::class.java)
        }

        /**
         * Starts the service. It will be started only if there isn't another instance already
         * running.
         *
         * @param context the application context.
         * @param category a specific category to update, or null for global update.
         * @param target defines what should be updated.
         * @return true if service newly started, false otherwise
         */
        fun start(context: Context, category: Category? = null, target: Target = Target.CHAPTERS /* SY --> */, group: Int = LibraryGroup.BY_DEFAULT, groupExtra: String? = null /* SY <-- */): Boolean {
            return if (!isRunning(context)) {
                val intent = Intent(context, LibraryUpdateService::class.java).apply {
                    putExtra(KEY_TARGET, target)
                    category?.let { putExtra(KEY_CATEGORY, it.id) }
                    // SY -->
                    putExtra(KEY_GROUP, group)
                    groupExtra?.let { putExtra(KEY_GROUP_EXTRA, it) }
                    // SY <--
                }
                ContextCompat.startForegroundService(context, intent)

                true
            } else {
                instance?.addMangaToQueue(category?.id ?: -1, group, groupExtra)
                false
            }
        }

        /**
         * Stops the service.
         *
         * @param context the application context.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, LibraryUpdateService::class.java))
        }
    }

    /**
     * Method called when the service is created. It injects dagger dependencies and acquire
     * the wake lock.
     */
    override fun onCreate() {
        super.onCreate()

        notifier = LibraryUpdateNotifier(this)
        wakeLock = acquireWakeLock(javaClass.name)

        startForeground(Notifications.ID_LIBRARY_PROGRESS, notifier.progressNotificationBuilder.build())
    }

    /**
     * Method called when the service is destroyed. It destroys subscriptions and releases the wake
     * lock.
     */
    override fun onDestroy() {
        updateJob?.cancel()
        ioScope?.cancel()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * This method needs to be implemented, but it's not used/needed.
     */
    override fun onBind(intent: Intent): IBinder? = null

    /**
     * Method called when the service receives an intent.
     *
     * @param intent the start intent from.
     * @param flags the flags of the command.
     * @param startId the start id of this command.
     * @return the start value of the command.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val target = intent.getSerializableExtra(KEY_TARGET) as? Target
            ?: return START_NOT_STICKY

        instance = this

        // Unsubscribe from any previous subscription if needed
        updateJob?.cancel()
        ioScope?.cancel()

        // Update favorite manga
        val categoryId = intent.getIntExtra(KEY_CATEGORY, -1)
        val group = intent.getIntExtra(KEY_GROUP, LibraryGroup.BY_DEFAULT)
        val groupExtra = intent.getStringExtra(KEY_GROUP_EXTRA)
        addMangaToQueue(categoryId, group, groupExtra)

        // Destroy service when completed or in case of an error.
        val handler = CoroutineExceptionHandler { _, exception ->
            logcat(LogPriority.ERROR, exception)
            stopSelf(startId)
        }
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        updateJob = ioScope?.launch(handler) {
            when (target) {
                Target.CHAPTERS -> updateChapterList()
                Target.COVERS -> updateCovers()
                Target.TRACKING -> updateTrackings()
                // SY -->
                Target.SYNC_FOLLOWS -> syncFollows()
                Target.PUSH_FAVORITES -> pushFavorites()
                // SY <--
            }
        }
        updateJob?.invokeOnCompletion { stopSelf(startId) }

        return START_REDELIVER_INTENT
    }

    /**
     * Adds list of manga to be updated.
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    fun addMangaToQueue(categoryId: Int, group: Int, groupExtra: String?) {
        val libraryManga = db.getLibraryMangas().executeAsBlocking()
        // SY -->
        val groupLibraryUpdateType = preferences.groupLibraryUpdateType().get()
        // SY <--

        val listToUpdate = if (categoryId != -1) {
            libraryManga.filter { it.category == categoryId }
            // SY -->
        } else if (
            group == LibraryGroup.BY_DEFAULT ||
            groupLibraryUpdateType == GroupLibraryMode.GLOBAL ||
            (groupLibraryUpdateType == GroupLibraryMode.ALL_BUT_UNGROUPED && group == LibraryGroup.UNGROUPED)
        ) {
            val categoriesToUpdate = preferences.libraryUpdateCategories().get().map(String::toInt)
            val listToInclude = if (categoriesToUpdate.isNotEmpty()) {
                libraryManga.filter { it.category in categoriesToUpdate }
            } else {
                libraryManga
            }

            val categoriesToExclude = preferences.libraryUpdateCategoriesExclude().get().map(String::toInt)
            val listToExclude = if (categoriesToExclude.isNotEmpty()) {
                libraryManga.filter { it.category in categoriesToExclude }.toSet()
            } else {
                emptySet()
            }

            listToInclude.minus(listToExclude)
        } else {
            when (group) {
                LibraryGroup.BY_TRACK_STATUS -> {
                    val trackingExtra = groupExtra?.toIntOrNull() ?: -1
                    val loggedServices = trackManager.services.filter { it.isLogged }
                    val tracks = db.getTracks().executeAsBlocking().groupBy { it.manga_id }
                    val statuses = loggedServices.associate {
                        it.id to it.getStatusList().associateWith(it::getStatus)
                    }

                    libraryManga.filter { manga ->
                        val status = tracks[manga.id]?.firstNotNullOfOrNull { track ->
                            statuses[track.sync_id]?.get(track.status)
                        } ?: "not tracked"
                        (trackManager.trackMap[status] ?: TrackManager.OTHER) == trackingExtra
                    }
                }
                LibraryGroup.BY_SOURCE -> {
                    val sourceExtra = groupExtra.nullIfBlank()?.toIntOrNull()
                    val source = libraryManga.map { it.source }
                        .distinct()
                        .sorted()
                        .getOrNull(sourceExtra ?: -1)

                    if (source != null) libraryManga.filter { it.source == source } else emptyList()
                }
                LibraryGroup.BY_STATUS -> {
                    val statusExtra = groupExtra?.toIntOrNull() ?: -1
                    libraryManga.filter {
                        it.status == statusExtra
                    }
                }
                LibraryGroup.UNGROUPED -> libraryManga
                else -> libraryManga
            }
            // SY <--
        }

        mangaToUpdate = listToUpdate
            .distinctBy { it.id }
            .sortedBy { it.title }

        // Warn when excessively checking a single source
        val maxUpdatesFromSource = mangaToUpdate
            .groupBy { it.source }
            .filterKeys { sourceManager.get(it) !is UnmeteredSource }
            .maxOfOrNull { it.value.size } ?: 0
        if (maxUpdatesFromSource > MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
            notifier.showQueueSizeWarningNotification()
        }
    }

    /**
     * Method that updates manga in [mangaToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each manga it calls [updateManga] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    private suspend fun updateChapterList() {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInteger(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<LibraryManga>()
        val newUpdates = CopyOnWriteArrayList<Pair<LibraryManga, Array<Chapter>>>()
        val skippedUpdates = CopyOnWriteArrayList<Pair<Manga, String?>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Manga, String?>>()
        val hasDownloads = AtomicBoolean(false)
        val loggedServices by lazy { trackManager.services.filter { it.isLogged } }
        val currentUnreadUpdatesCount = preferences.unreadUpdatesCount().get()
        val restrictions = preferences.libraryUpdateMangaRestriction().get()

        withIOContext {
            mangaToUpdate.groupBy { it.source }
                .filterNot { it.key in LIBRARY_UPDATE_EXCLUDED_SOURCES }
                .values
                .map { mangaInSource ->
                    async {
                        semaphore.withPermit {
                            mangaInSource.forEach { manga ->
                                if (updateJob?.isActive != true) {
                                    return@async
                                }

                                // Don't continue to update if manga not in library
                                db.getManga(manga.id!!).executeAsBlocking() ?: return@forEach

                                withUpdateNotification(
                                    currentlyUpdatingManga,
                                    progressCount,
                                    manga,
                                ) { mangaWithNotif ->
                                    try {
                                        when {
                                            MANGA_NON_COMPLETED in restrictions && mangaWithNotif.status == SManga.COMPLETED ->
                                                skippedUpdates.add(mangaWithNotif to getString(R.string.skipped_reason_completed))

                                            MANGA_HAS_UNREAD in restrictions && mangaWithNotif.unreadCount != 0 ->
                                                skippedUpdates.add(mangaWithNotif to getString(R.string.skipped_reason_not_caught_up))

                                            MANGA_NON_READ in restrictions && mangaWithNotif.totalChapters > 0 && !mangaWithNotif.hasStarted ->
                                                skippedUpdates.add(mangaWithNotif to getString(R.string.skipped_reason_not_started))

                                            else -> {
                                                // Convert to the manga that contains new chapters
                                                val (newChapters, _) = updateManga(mangaWithNotif, loggedServices)

                                                if (newChapters.isNotEmpty()) {
                                                    if (mangaWithNotif.shouldDownloadNewChapters(db, preferences)) {
                                                        downloadChapters(mangaWithNotif, newChapters)
                                                        hasDownloads.set(true)
                                                    }

                                                    // Convert to the manga that contains new chapters
                                                    newUpdates.add(
                                                        mangaWithNotif to newChapters.sortedByDescending { ch -> ch.source_order }
                                                            .toTypedArray(),
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        val errorMessage = when (e) {
                                            is NoChaptersException -> getString(R.string.no_chapters_error)
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is SourceManager.SourceNotInstalledException -> getString(R.string.loader_not_implemented_error)
                                            else -> e.message
                                        }
                                        failedUpdates.add(mangaWithNotif to errorMessage)
                                    }

                                    if (preferences.autoUpdateTrackers()) {
                                        updateTrackings(mangaWithNotif, loggedServices)
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            notifier.showUpdateNotifications(newUpdates)
            val newChapterCount = newUpdates.sumOf { it.second.size }
            preferences.unreadUpdatesCount().set(currentUnreadUpdatesCount + newChapterCount)
            if (hasDownloads.get()) {
                DownloadService.start(this)
            }
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(this),
            )
        }
        if (skippedUpdates.isNotEmpty()) {
            notifier.showUpdateSkippedNotification(skippedUpdates.size)
        }
    }

    private fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        // SY -->
        val chapterFilter = if (manga.source == MERGED_SOURCE_ID) {
            db.getMergedMangaReferences(manga.id!!).executeAsBlocking()
                .filterNot { it.downloadChapters }
                .mapNotNull { it.mangaId } + manga.id!!
        } else emptyList()
        // SY <--
        downloadManager.downloadChapters(manga, /* SY --> */ chapters.filterNot { it.manga_id in chapterFilter } /* SY <-- */, false)
    }

    /**
     * Updates the chapters for the given manga and adds them to the database.
     *
     * @param manga the manga to update.
     * @return a pair of the inserted and removed chapters.
     */
    private suspend fun updateManga(manga: Manga, loggedServices: List<TrackService>): Pair<List<Chapter>, List<Chapter>> {
        val source = sourceManager.getOrStub(manga.source).getMainSource()

        var updatedManga: SManga = manga

        // Update manga details metadata
        if (preferences.autoUpdateMetadata()) {
            val updatedMangaDetails = source.getMangaDetails(manga.toMangaInfo())
            val sManga = updatedMangaDetails.toSManga()
            // Avoid "losing" existing cover
            if (!sManga.thumbnail_url.isNullOrEmpty()) {
                manga.prepUpdateCover(coverCache, sManga, false)
            } else {
                sManga.thumbnail_url = manga.thumbnail_url
            }

            updatedManga = sManga
        }

        // SY -->
        if (source.isMdBasedSource() && trackManager.mdList in loggedServices) {
            val handler = CoroutineExceptionHandler { _, exception ->
                xLogE("Error adding initial track for ${manga.title}", exception)
            }
            ioScope?.launch(handler) {
                val tracks = db.getTracks(manga).executeAsBlocking()
                if (tracks.isEmpty() || tracks.none { it.sync_id == TrackManager.MDLIST }) {
                    val track = trackManager.mdList.createInitialTracker(manga)
                    db.insertTrack(trackManager.mdList.refresh(track)).executeAsBlocking()
                }
            }
        }

        if (source is MergedSource) {
            return source.fetchChaptersAndSync(manga, false)
        }
        // SY <--

        val chapters = source.getChapterList(updatedManga.toMangaInfo())
            .map { it.toSChapter() }

        // Get manga from database to account for if it was removed during the update
        val dbManga = db.getManga(manga.id!!).executeAsBlocking()
            ?: return Pair(emptyList(), emptyList())

        // Copy into [dbManga] to retain favourite value
        dbManga.copyFrom(updatedManga)
        db.insertManga(dbManga).executeAsBlocking()

        // [dbmanga] was used so that manga data doesn't get overwritten
        // in case manga gets new chapter
        return syncChaptersWithSource(db, chapters, dbManga, source)
    }

    private suspend fun updateCovers() {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInteger(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<LibraryManga>()

        withIOContext {
            mangaToUpdate.groupBy { it.source }
                .values
                .map { mangaInSource ->
                    async {
                        semaphore.withPermit {
                            mangaInSource.forEach { manga ->
                                if (updateJob?.isActive != true) {
                                    return@async
                                }

                                withUpdateNotification(
                                    currentlyUpdatingManga,
                                    progressCount,
                                    manga,
                                ) { mangaWithNotif ->
                                    sourceManager.get(mangaWithNotif.source)?.let { source ->
                                        try {
                                            val networkManga =
                                                source.getMangaDetails(mangaWithNotif.toMangaInfo())
                                            val sManga = networkManga.toSManga()
                                            mangaWithNotif.prepUpdateCover(coverCache, sManga, true)
                                            sManga.thumbnail_url?.let {
                                                mangaWithNotif.thumbnail_url = it
                                                db.insertManga(mangaWithNotif).executeAsBlocking()
                                            }
                                        } catch (e: Throwable) {
                                            // Ignore errors and continue
                                            logcat(LogPriority.ERROR, e)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        coverCache.clearMemoryCache()
        notifier.cancelProgressNotification()
    }

    /**
     * Method that updates the metadata of the connected tracking services. It's called in a
     * background thread, so it's safe to do heavy operations or network calls here.
     */
    private suspend fun updateTrackings() {
        var progressCount = 0
        val loggedServices = trackManager.services.filter { it.isLogged }

        mangaToUpdate.forEach { manga ->
            if (updateJob?.isActive != true) {
                return
            }

            notifier.showProgressNotification(listOf(manga), progressCount++, mangaToUpdate.size)

            // Update the tracking details.
            updateTrackings(manga, loggedServices)
        }

        notifier.cancelProgressNotification()
    }

    private suspend fun updateTrackings(manga: LibraryManga, loggedServices: List<TrackService>) {
        db.getTracks(manga).executeAsBlocking()
            .map { track ->
                supervisorScope {
                    async {
                        val service = trackManager.getService(track.sync_id)
                        if (service != null && service in loggedServices) {
                            try {
                                val updatedTrack = service.refresh(track)
                                db.insertTrack(updatedTrack).executeAsBlocking()

                                if (service is EnhancedTrackService) {
                                    syncChaptersWithTrackServiceTwoWay(db, db.getChapters(manga).executeAsBlocking(), track, service)
                                }
                            } catch (e: Throwable) {
                                // Ignore errors and continue
                                logcat(LogPriority.ERROR, e)
                            }
                        }
                    }
                }
            }
            .awaitAll()
    }

    private suspend fun withUpdateNotification(
        updatingManga: CopyOnWriteArrayList<LibraryManga>,
        completed: AtomicInteger,
        manga: LibraryManga,
        block: suspend (LibraryManga) -> Unit,
    ) {
        if (updateJob?.isActive != true) {
            return
        }

        updatingManga.add(manga)
        notifier.showProgressNotification(
            updatingManga,
            completed.get(),
            mangaToUpdate.size,
        )

        block(manga)

        if (updateJob?.isActive != true) {
            return
        }

        updatingManga.remove(manga)
        completed.andIncrement
        notifier.showProgressNotification(
            updatingManga,
            completed.get(),
            mangaToUpdate.size,
        )
    }

    // SY -->
    /**
     * filter all follows from Mangadex and only add reading or rereading manga to library
     */
    private suspend fun syncFollows() {
        var count = 0
        val mangaDex = MdUtil.getEnabledMangaDex(preferences, sourceManager) ?: return
        val syncFollowStatusInts = preferences.mangadexSyncToLibraryIndexes().get().map { it.toInt() }

        val size: Int
        mangaDex.fetchAllFollows()
            .filter { (_, metadata) ->
                syncFollowStatusInts.contains(metadata.followStatus)
            }
            .also { size = it.size }
            .forEach { (networkManga, metadata) ->
                if (updateJob?.isActive != true) {
                    return
                }

                count++
                notifier.showProgressNotification(listOf(networkManga), count, size)

                var dbManga = db.getManga(networkManga.url, mangaDex.id)
                    .executeOnIO()
                if (dbManga == null) {
                    dbManga = Manga.create(
                        networkManga.url,
                        networkManga.title,
                        mangaDex.id,
                    )
                    dbManga.date_added = System.currentTimeMillis()
                }

                dbManga.copyFrom(networkManga)
                dbManga.favorite = true
                val id = db.insertManga(dbManga).executeOnIO().insertedId()
                if (id != null) {
                    metadata.mangaId = id
                    db.insertFlatMetadataAsync(metadata.flatten()).await()
                }
            }

        notifier.cancelProgressNotification()
    }

    /**
     * Method that updates the all mangas which are not tracked as "reading" on mangadex
     */
    private suspend fun pushFavorites() {
        var count = 0
        val listManga = db.getFavoriteMangas().executeAsBlocking().filter { it.source in mangaDexSourceIds }

        // filter all follows from Mangadex and only add reading or rereading manga to library
        if (trackManager.mdList.isLogged) {
            listManga.forEach { manga ->
                if (updateJob?.isActive != true) {
                    return
                }

                count++
                notifier.showProgressNotification(listOf(manga), count, listManga.size)

                // Get this manga's trackers from the database
                val dbTracks = db.getTracks(manga).executeAsBlocking()

                // find the mdlist entry if its unfollowed the follow it
                val tracker = TrackItem(dbTracks.firstOrNull { it.sync_id == TrackManager.MDLIST } ?: trackManager.mdList.createInitialTracker(manga), trackManager.mdList)

                if (tracker.track?.status == FollowStatus.UNFOLLOWED.int) {
                    tracker.track.status = FollowStatus.READING.int
                    val updatedTrack = tracker.service.update(tracker.track)
                    db.insertTrack(updatedTrack).executeOnIO()
                }
            }
        }

        notifier.cancelProgressNotification()
    }
    // SY <--

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: List<Pair<Manga, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = createFileInCacheDir("tachiyomi_update_errors.txt")
                file.bufferedWriter().use { out ->
                    out.write(getString(R.string.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n")
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("\n! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}

private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60
private const val ERROR_LOG_HELP_URL = "https://tachiyomi.org/help/guides/troubleshooting"
