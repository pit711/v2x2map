package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MarkerLayer(private val map: MapView, private val context: Context) {

    private data class Entry(
        val marker: Marker,
        val createdMs: Long,
    )

    private val entries = ArrayDeque<Entry>()

    // Path tracking for moving objects (CAM)
    private val pathPoints   = mutableMapOf<Long, MutableList<GeoPoint>>()
    private val pathLines    = mutableMapOf<Long, Polyline>()
    private val pathLastSeen = mutableMapOf<Long, Long>()

    fun add(f: Frame) {
        val ll = f.latLon ?: return
        val pt = GeoPoint(ll.first, ll.second)

        if (f.msgType == ItsG5Decoder.MsgType.CAM && f.stationId != null) {
            updatePath(f.stationId, pt)
        }

        val m = Marker(map).apply {
            position = pt
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = makeDrawable(f.msgType)
            f.headingDeg?.let { rotation = it.toFloat() }
            title   = buildTitle(f)
            snippet = buildSnippet(f)
        }
        map.overlays.add(m)
        entries.addLast(Entry(m, System.currentTimeMillis()))
        prune()
    }

    fun prune() {
        val now   = System.currentTimeMillis()
        val ttlMs = Prefs.markerTtlMinutes(context).toLong() * 60_000L
        var changed = false

        while (entries.isNotEmpty() && now - entries.first().createdMs > ttlMs) {
            map.overlays.remove(entries.removeFirst().marker)
            changed = true
        }
        while (entries.size > MAX_MARKERS) {
            map.overlays.remove(entries.removeFirst().marker)
            changed = true
        }

        val stalePaths = pathLastSeen.entries
            .filter { now - it.value > ttlMs }
            .map { it.key }
        for (sid in stalePaths) {
            pathLines.remove(sid)?.let { map.overlays.remove(it); changed = true }
            pathPoints.remove(sid)
            pathLastSeen.remove(sid)
        }

        if (changed) map.invalidate()
    }

    fun clear() {
        for (e in entries) map.overlays.remove(e.marker)
        entries.clear()
        for (poly in pathLines.values) map.overlays.remove(poly)
        pathLines.clear()
        pathPoints.clear()
        pathLastSeen.clear()
        map.invalidate()
    }

    private fun updatePath(stationId: Long, pt: GeoPoint) {
        val pts = pathPoints.getOrPut(stationId) { mutableListOf() }
        pts.add(pt)
        if (pts.size > PATH_MAX_POINTS) pts.removeAt(0)

        val line = pathLines.getOrPut(stationId) {
            Polyline().also { poly ->
                poly.outlinePaint.color       = ItsG5Decoder.MsgType.CAM.color
                poly.outlinePaint.strokeWidth = 5f
                poly.outlinePaint.alpha       = 160
                map.overlays.add(0, poly)   // insert below all markers
            }
        }
        line.setPoints(pts)
        pathLastSeen[stationId] = System.currentTimeMillis()
        map.invalidate()
    }

    private fun makeDrawable(t: ItsG5Decoder.MsgType): Drawable {
        val resId = when (t) {
            ItsG5Decoder.MsgType.CAM    -> R.drawable.ic_marker_car
            ItsG5Decoder.MsgType.SPATEM -> R.drawable.ic_marker_trafficlight
            ItsG5Decoder.MsgType.MAPEM  -> R.drawable.ic_marker_intersection
            ItsG5Decoder.MsgType.DENM   -> R.drawable.ic_marker_hazard
            else                        -> R.drawable.ic_marker_dot
        }
        val d = ContextCompat.getDrawable(context, resId)!!.mutate()
        DrawableCompat.setTint(d, t.color)
        return d
    }

    private fun buildTitle(f: Frame): String {
        val sid = f.stationId?.let { "#%08x".format(it.toInt()) } ?: ""
        return if (sid.isEmpty()) "${f.msgType.short} frame #${f.seq}"
               else "${f.msgType.short} $sid"
    }

    private fun buildSnippet(f: Frame): String {
        val (lat, lon) = f.latLon ?: return "len=${f.len}"
        val sb = StringBuilder()
        sb.append("lat=%.6f  lon=%.6f\n".format(lat, lon))
        f.speedMps?.takeIf { it > 0.5 }?.let {
            sb.append("speed=%.1f km/h\n".format(it * 3.6))
        }
        f.headingDeg?.let { sb.append("hdg=%.0f°\n".format(it)) }
        sb.append("len=${f.len}")
        return sb.toString()
    }

    companion object {
        private const val MAX_MARKERS    = 500
        private const val PATH_MAX_POINTS = 50
    }
}
