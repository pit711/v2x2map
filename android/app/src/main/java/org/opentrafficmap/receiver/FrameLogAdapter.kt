package org.opentrafficmap.receiver

import android.graphics.Color
import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Grouped log adapter. One row per station (stationId + msgType). Tapping a row
 * expands it to show the last [FRAME_HISTORY] individual frames.
 *
 * Columns: type icon | station ID | summary | last-seen | distance | lock | chevron
 *
 * At high packet rates this stays readable because 20 active vehicles → 20 rows,
 * not 300 rows/min. Batch updates via [addFrames] use notifyDataSetChanged() for
 * stability; individual insertions are never issued.
 */
class FrameLogAdapter(
    private val context: android.content.Context,
    private val onFrameClick: (Frame) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Updated by MainActivity on every location fix for distance column. */
    @Volatile var userLocation: Location? = null

    // ── internal data model ──────────────────────────────────────────────────

    data class StationEntry(
        val key: String,
        val msgType: ItsG5Decoder.MsgType,
        val stationId: Long?,
        val recentFrames: ArrayDeque<Frame> = ArrayDeque(),
        var frameCount: Int = 0,
        var lastSeen: Instant = Instant.EPOCH,
        var expanded: Boolean = false,
        var lastSecured: Boolean? = null,
        var lastLatLon: Pair<Double, Double>? = null,
    )

    /** Flat display list — either StationEntry (header row) or Frame (expanded row). */
    private val stations    = HashMap<String, StationEntry>()
    private val displayList = mutableListOf<Any>()
    private val timeFmt     = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Insert a batch of frames. Groups them by station + message type.
     * Must be called on the main thread.
     */
    fun addFrames(frames: List<Frame>) {
        if (frames.isEmpty()) return
        for (frame in frames) {
            val key   = stationKey(frame)
            val entry = stations.getOrPut(key) { StationEntry(key, frame.msgType, frame.stationId) }
            entry.frameCount++
            entry.lastSeen    = frame.wallTime
            entry.lastSecured = frame.secured
            entry.lastLatLon  = frame.latLon ?: entry.lastLatLon
            entry.recentFrames.addFirst(frame)
            if (entry.recentFrames.size > FRAME_HISTORY) entry.recentFrames.removeLast()
        }
        // Evict least-recently-seen stations beyond cap
        while (stations.size > MAX_STATIONS) {
            stations.values.minByOrNull { it.lastSeen }?.key?.let { stations.remove(it) }
        }
        rebuildAndNotify()
    }

    fun clear() {
        stations.clear()
        displayList.clear()
        notifyDataSetChanged()
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    private fun stationKey(f: Frame): String {
        val sid = f.stationId?.toString() ?: "anon"
        return "${f.msgType.name}|$sid"
    }

    private fun rebuildAndNotify() {
        displayList.clear()
        stations.values.sortedByDescending { it.lastSeen }.forEach { entry ->
            displayList.add(entry)
            if (entry.expanded) entry.recentFrames.forEach { displayList.add(it) }
        }
        notifyDataSetChanged()
    }

    // ── RecyclerView plumbing ────────────────────────────────────────────────

    override fun getItemCount() = displayList.size
    override fun getItemViewType(pos: Int) = if (displayList[pos] is StationEntry) TYPE_STATION else TYPE_FRAME

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_STATION)
            StationVH(inf.inflate(R.layout.item_station, parent, false))
        else
            FrameVH(inf.inflate(R.layout.item_frame, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayList[position]) {
            is StationEntry -> bindStation(holder as StationVH, item)
            is Frame        -> bindFrame(holder as FrameVH, item)
        }
    }

    // ── station row ──────────────────────────────────────────────────────────

    private fun bindStation(vh: StationVH, entry: StationEntry) {
        // Type icon
        val iconRes = when (entry.msgType) {
            ItsG5Decoder.MsgType.CAM    -> R.drawable.ic_marker_car
            ItsG5Decoder.MsgType.SPATEM -> R.drawable.ic_marker_trafficlight
            ItsG5Decoder.MsgType.DENM   -> R.drawable.ic_marker_hazard
            ItsG5Decoder.MsgType.MAPEM  -> R.drawable.ic_marker_intersection
            else                        -> R.drawable.ic_marker_dot
        }
        vh.icon.setImageDrawable(
            ContextCompat.getDrawable(vh.itemView.context, iconRes)!!.mutate().also {
                androidx.core.graphics.drawable.DrawableCompat.setTint(it, entry.msgType.color)
            }
        )

        // Station title: type badge + ID
        val sid = entry.stationId?.let { "#%08x".format(it.toInt()) } ?: "—"
        vh.title.text = "${entry.msgType.short}  $sid"
        vh.title.setTextColor(entry.msgType.color)

        // Summary
        vh.summary.text = buildSummary(entry)

        // Last-seen time
        vh.time.text = timeFmt.format(Date.from(entry.lastSeen))

        // Distance
        vh.dist.text = formatDistance(entry.lastLatLon, userLocation)

        // Lock icon
        val sec = entry.lastSecured
        if (sec == null) {
            vh.lock.visibility = View.INVISIBLE
        } else {
            vh.lock.visibility = View.VISIBLE
            vh.lock.setImageResource(if (sec) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
        }

        // Chevron rotation
        vh.chevron.rotation = if (entry.expanded) 180f else 0f

        // Frame count badge (in summary if no summary text)
        // Already shown inline via buildSummary

        vh.itemView.setOnClickListener {
            entry.expanded = !entry.expanded
            rebuildAndNotify()
        }
    }

    private fun buildSummary(entry: StationEntry): String {
        val f = entry.recentFrames.firstOrNull()
        val countStr = "${entry.frameCount}×"
        val detail = when (entry.msgType) {
            ItsG5Decoder.MsgType.CAM -> buildString {
                if (f != null) {
                    f.speedMps?.takeIf { it > 0.5 }?.let { append("%.0f km/h ".format(it * 3.6)) }
                    f.headingDeg?.let {
                        val card = arrayOf("N","NE","E","SE","S","SW","W","NW")
                        append(card[((it / 45.0 + 0.5).toInt() % 8).coerceIn(0,7)])
                        append("  ")
                    }
                    f.latLon?.let { (lat, lon) -> append("%.4f,%.4f".format(lat, lon)) }
                }
            }.trim().ifEmpty { "—" }
            ItsG5Decoder.MsgType.SPATEM -> when (f?.spatPhase) {
                SpatTemParser.Phase.RED    -> context.getString(R.string.log_phase_red)
                SpatTemParser.Phase.YELLOW -> context.getString(R.string.log_phase_yellow)
                SpatTemParser.Phase.GREEN  -> context.getString(R.string.log_phase_green)
                else                       -> context.getString(R.string.log_phase_unknown)
            }
            ItsG5Decoder.MsgType.DENM  -> {
                val cause = f?.denmCause
                if (cause != null) cause.label() + cause.sublabel().let { if (it.isNotEmpty()) " $it" else "" }
                else context.getString(R.string.log_hazard)
            }
            ItsG5Decoder.MsgType.MAPEM -> context.getString(R.string.log_intersection)
            else -> f?.let { "len=${it.len}" } ?: "—"
        }
        return "$countStr  $detail"
    }

    private fun formatDistance(latLon: Pair<Double, Double>?, userLoc: Location?): String {
        if (latLon == null || userLoc == null) return "—"
        val result = FloatArray(1)
        Location.distanceBetween(userLoc.latitude, userLoc.longitude,
                                 latLon.first, latLon.second, result)
        return when {
            result[0] < 1000  -> "%.0f m".format(result[0])
            result[0] < 10000 -> "%.1f km".format(result[0] / 1000)
            else               -> "%.0f km".format(result[0] / 1000)
        }
    }

    // ── expanded frame row ───────────────────────────────────────────────────

    private fun bindFrame(vh: FrameVH, frame: Frame) {
        vh.itemView.setBackgroundColor(0x0A000000)   // faint tint to visually indent
        vh.itemView.setOnClickListener { onFrameClick(frame) }
        vh.time.text = timeFmt.format(Date.from(frame.wallTime))
        vh.len.text  = frame.len.toString()
        vh.hex.text  = frame.hexPreview()
        when (frame.etherType) {
            null   -> { vh.et.text = "?";      vh.et.setTextColor(Color.parseColor("#F0883E")) }
            0x8947 -> { vh.et.text = "ITS-G5"; vh.et.setTextColor(Color.parseColor("#7EE787")) }
            else   -> { vh.et.text = "0x%04x".format(frame.etherType)
                        vh.et.setTextColor(Color.parseColor("#8B949E")) }
        }
    }

    // ── ViewHolder classes ───────────────────────────────────────────────────

    class StationVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon:    ImageView = v.findViewById(R.id.stationIcon)
        val title:   TextView  = v.findViewById(R.id.stationTitle)
        val summary: TextView  = v.findViewById(R.id.stationSummary)
        val time:    TextView  = v.findViewById(R.id.stationTime)
        val dist:    TextView  = v.findViewById(R.id.stationDist)
        val lock:    ImageView = v.findViewById(R.id.stationLock)
        val chevron: ImageView = v.findViewById(R.id.stationChevron)
    }

    class FrameVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.fTime)
        val et:   TextView = v.findViewById(R.id.fEt)
        val len:  TextView = v.findViewById(R.id.fLen)
        val hex:  TextView = v.findViewById(R.id.fHex)
    }

    companion object {
        private const val TYPE_STATION   = 0
        private const val TYPE_FRAME     = 1
        private const val MAX_STATIONS   = 150  // max unique stations kept
        private const val FRAME_HISTORY  = 20   // frames kept per station for expand view
    }
}
