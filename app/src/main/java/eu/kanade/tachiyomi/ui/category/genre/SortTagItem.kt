package eu.kanade.tachiyomi.ui.category.genre

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R

/**
 * Category item for a recycler view.
 */
class SortTagItem(val tag: String) : AbstractFlexibleItem<SortTagHolder>() {

    /**
     * Whether this item is currently selected.
     */
    var isSelected = false

    /**
     * Returns the layout resource for this item.
     */
    override fun getLayoutRes(): Int {
        return R.layout.categories_item
    }

    /**
     * Returns a new view holder for this item.
     *
     * @param view The view of this item.
     * @param adapter The adapter of this item.
     */
    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): SortTagHolder {
        return SortTagHolder(view, adapter as SortTagAdapter)
    }

    /**
     * Binds the given view holder with this item.
     *
     * @param adapter The adapter of this item.
     * @param holder The holder to bind.
     * @param position The position of this item in the adapter.
     * @param payloads List of partial changes.
     */
    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: SortTagHolder,
        position: Int,
        payloads: List<Any?>?,
    ) {
        holder.bind(tag)
    }

    /**
     * Returns true if this item is draggable.
     */
    override fun isDraggable(): Boolean {
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is SortTagItem) {
            return tag == other.tag
        }
        return false
    }

    override fun hashCode(): Int {
        return tag.hashCode()
    }
}
