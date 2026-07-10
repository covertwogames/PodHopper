package au.com.shiftyjelly.pocketcasts

import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * PodHopper: settings row showing one stacked bar of the head unit's storage. Green is
 * PodHopper's own usage (downloads plus streaming cache), red is everything else used on the
 * device, grey is free space. Values are set by the settings fragment after it measures the
 * folders off the main thread; until then the row shows a calculating placeholder.
 */
class StorageBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var appBytes: Long = 0
    private var otherUsedBytes: Long = 0
    private var freeBytes: Long = 0
    private var hasData = false

    init {
        layoutResource = R.layout.preference_storage_bar
        isSelectable = false
        isPersistent = false
    }

    fun setUsage(appBytes: Long, otherUsedBytes: Long, freeBytes: Long) {
        this.appBytes = appBytes.coerceAtLeast(0)
        this.otherUsedBytes = otherUsedBytes.coerceAtLeast(0)
        this.freeBytes = freeBytes.coerceAtLeast(0)
        this.hasData = true
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val segmentApp = holder.findViewById(R.id.storage_segment_app)
        val segmentOther = holder.findViewById(R.id.storage_segment_other)
        val segmentFree = holder.findViewById(R.id.storage_segment_free)
        val labelView = holder.findViewById(R.id.storage_bar_summary) as? TextView

        if (!hasData) {
            labelView?.text = context.getString(R.string.podhopper_storage_calculating)
            setSegmentWeight(segmentApp, 0f)
            setSegmentWeight(segmentOther, 0f)
            setSegmentWeight(segmentFree, 1f)
            return
        }

        val total = (appBytes + otherUsedBytes + freeBytes).coerceAtLeast(1)
        // Keep the PodHopper segment visible even when its share rounds to nothing: a sliver of
        // green communicates "small" better than an invisible segment communicates anything.
        val appWeight = (appBytes.toFloat() / total).coerceAtLeast(if (appBytes > 0) 0.02f else 0f)
        val otherWeight = (otherUsedBytes.toFloat() / total)
        val freeWeight = (1f - appWeight - otherWeight).coerceAtLeast(0f)
        setSegmentWeight(segmentApp, appWeight)
        setSegmentWeight(segmentOther, otherWeight)
        setSegmentWeight(segmentFree, freeWeight)

        labelView?.text = context.getString(
            R.string.podhopper_storage_summary,
            Formatter.formatShortFileSize(context, appBytes),
            Formatter.formatShortFileSize(context, appBytes + otherUsedBytes),
            Formatter.formatShortFileSize(context, appBytes + otherUsedBytes + freeBytes),
        )
    }

    private fun setSegmentWeight(view: View?, weight: Float) {
        val params = view?.layoutParams as? LinearLayout.LayoutParams ?: return
        params.weight = weight
        params.width = 0
        view.layoutParams = params
    }
}
