package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.serialization.Serializable

@Serializable
data class Rating(
    val rating: Int? = null,
)

fun Rating.copyTo(track: Track): Track {
    return track.apply {
        this.score = rating?.toFloat() ?: 0f
    }
}
