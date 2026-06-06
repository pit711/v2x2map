package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Separate map overlay layer for OpenTrafficMap live entities.
 * All OTM markers use a warm amber colour scheme so they are instantly
 * distinguishable from locally received ITS-G5 frames.
 */
class OtmLiveLayer(private val map: MapView, private val context: Context) {

    // All points ever received (even if currently outside the visible area)
    private val allPoints = LinkedHashMap<String, OtmPoint>()
    // Only markers that are currently shown on the map
    private val markers = HashMap<String, Marker>()

    fun upsert(point: OtmPoint, boundingBox: BoundingBox? = null) {
        allPoints[point.mac] = point
        // evict oldest if we accumulate too many points
        while (allPoints.size > MAX_STORED_POINTS) allPoints.remove(allPoints.keys.first())
        if (boundingBox == null || inBounds(point.lat, point.lon, boundingBox)) {
            showOrUpdate(point)
        } else {
            removeMarker(point.mac)
        }
    }

    fun delete(mac: String) {
        allPoints.remove(mac)
        removeMarker(mac)
    }

    fun refilter(bb: BoundingBox) {
        // Hide markers outside new bounds
        markers.keys.filter { mac ->
            val p = allPoints[mac]
            p == null || !inBounds(p.lat, p.lon, bb)
        }.forEach { removeMarker(it) }

        // Show points now inside bounds
        for (p in allPoints.values) {
            if (inBounds(p.lat, p.lon, bb) && !markers.containsKey(p.mac)) showOrUpdate(p)
        }
        map.invalidate()
    }

    fun clear() {
        allPoints.clear()
        markers.values.forEach { map.overlays.remove(it) }
        markers.clear()
        map.invalidate()
    }

    fun visibleCount() = markers.size
    fun totalCount()   = allPoints.size

    private fun showOrUpdate(point: OtmPoint) {
        val m = markers.getOrPut(point.mac) {
            Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(this)
            }
        }
        m.position = GeoPoint(point.lat, point.lon)
        m.icon     = makeIcon(point)
        m.title    = "OTM · ${kindLabel(point.kind)} · ${point.mac}"
        m.snippet  = buildSnippet(point)
        point.headingDeg?.let { m.rotation = it.toFloat() }
        map.invalidate()
    }

    private fun removeMarker(mac: String) {
        markers.remove(mac)?.let { map.overlays.remove(it) }
    }

    private fun makeIcon(point: OtmPoint): Drawable {
        val dm     = context.resources.displayMetrics
        val iconPx = (32 * dm.density).toInt()
        val hasSpd = point.speedKmh != null && point.speedKmh > 1.0
        val textH  = if (hasSpd) (13 * dm.density).toInt() else 0

        val bm = Bitmap.createBitmap(iconPx, iconPx + textH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bm)

        val resId = when (point.kind) {
            "vehicle", "tram", "bus", "emergency",
            "cyclist", "pedestrian" -> R.drawable.ic_marker_car
            "traffic-light"         -> R.drawable.ic_marker_trafficlight
            else                    -> R.drawable.ic_marker_dot
        }
        val d = ContextCompat.getDrawable(context, resId)!!.mutate()
        DrawableCompat.setTint(d, otmColor(point.kind))
        cv.save()
        point.headingDeg?.let { cv.rotate(it.toFloat(), iconPx / 2f, iconPx / 2f) }
        d.setBounds(2, 2, iconPx - 2, iconPx - 2)
        d.draw(cv)
        cv.restore()

        if (hasSpd) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize  = 9 * dm.density
                textAlign = Paint.Align.CENTER
                color     = 0xFFFFFFFF.toInt()
                setShadowLayer(1.5f * dm.density, 0f, 0.5f * dm.density, 0xFF000000.toInt())
            }
            cv.drawText("%.0f km/h".format(point.speedKmh!!), iconPx / 2f, iconPx + 10 * dm.density, paint)
        }
        return BitmapDrawable(context.resources, bm)
    }

    private fun buildSnippet(p: OtmPoint): String = buildString {
        append("lat=%.5f  lon=%.5f\n".format(p.lat, p.lon))
        p.speedKmh?.takeIf { it > 0.5 }?.let { append("%.0f km/h  ".format(it)) }
        p.headingDeg?.let { append("%.0f°".format(it)) }
        if (isNotEmpty() && !endsWith("\n")) append("\n")
        append("OTM Live")
    }

    private fun inBounds(lat: Double, lon: Double, bb: BoundingBox) =
        lat in bb.latSouth..bb.latNorth && lon in bb.lonWest..bb.lonEast

    private fun kindLabel(kind: String) = when (kind) {
        "vehicle"       -> "Fahrzeug"
        "tram"          -> "Straßenbahn"
        "bus"           -> "Bus"
        "emergency"     -> "Einsatzfahrzeug"
        "traffic-light" -> "Ampel"
        "rsu"           -> "RSU"
        "cyclist"       -> "Fahrrad"
        "pedestrian"    -> "Fußgänger"
        else            -> kind
    }

    companion object {
        private const val MAX_STORED_POINTS = 8_000

        fun otmColor(kind: String): Int = when (kind) {
            "vehicle"                 -> 0xFFFF8F00.toInt()   // amber
            "tram", "bus"             -> 0xFFFF6D00.toInt()   // deep orange
            "emergency"               -> 0xFFF44336.toInt()   // red
            "traffic-light"           -> 0xFF00BFA5.toInt()   // teal
            "cyclist"                 -> 0xFF76FF03.toInt()   // light green
            "pedestrian"              -> 0xFFFFD600.toInt()   // yellow
            "rsu"                     -> 0xFFFFD600.toInt()   // yellow
            else                      -> 0xFFFF8F00.toInt()   // amber default
        }
    }
}
