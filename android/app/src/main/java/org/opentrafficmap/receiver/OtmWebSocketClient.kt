package org.opentrafficmap.receiver

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Connects to the OpenTrafficMap WebSocket live feed and delivers parsed
 * entity updates to the main thread via [onPoints].
 *
 * Binary frames are gzip-compressed JSON; text frames are plain JSON.
 * Reconnects automatically with exponential back-off as long as [isRunning].
 */
class OtmWebSocketClient(
    private val onPoints: (upserts: List<OtmPoint>, deletes: List<String>) -> Unit
) {
    private val tag = "OtmWsClient"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isRunning = false
    @Volatile var isConnected = false
    private var ws: WebSocket? = null
    private var retryDelayMs = 2_000L

    fun start() {
        isRunning = true
        retryDelayMs = 2_000L
        connect()
    }

    fun stop() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
        isConnected = false
    }

    private fun connect() {
        val req = Request.Builder()
            .url("wss://opentrafficmap.org/ws_ext?gzip=true")
            .header("Origin", "https://opentrafficmap.org")
            .header("User-Agent", "V2X2MAP-Android")
            .build()
        ws = httpClient.newWebSocket(req, WsListener())
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        mainHandler.postDelayed({
            if (isRunning && !isConnected) connect()
        }, retryDelayMs)
        retryDelayMs = minOf(retryDelayMs * 2, 30_000L)
    }

    private fun parse(json: String) {
        try {
            val obj  = JSONObject(json)
            val type = obj.optString("type")

            when (type) {
                "delta", "snapshot" -> parseDelta(obj)
                "traffic-light-map-batch" -> parseMapBatch(obj)
                // "hello", "node-statuses", "management-nodes" — ignored
            }
        } catch (e: Exception) {
            Log.w(tag, "parse error", e)
        }
    }

    /**
     * Parses delta/snapshot messages.
     *
     * Delta:    top-level "upsertPoints" array + "deletePoints" array
     * Snapshot: points are in "points.features" (GeoJSON FeatureCollection)
     *
     * Both formats contain GeoJSON Features with the same properties schema.
     */
    private fun parseDelta(obj: JSONObject) {
        val upserts = mutableListOf<OtmPoint>()
        val deletes = mutableListOf<String>()

        // Snapshot wraps features in points.features; delta puts them directly in upsertPoints
        val upArr = obj.optJSONArray("upsertPoints")
            ?: obj.optJSONObject("points")?.optJSONArray("features")

        if (upArr != null) {
            for (i in 0 until upArr.length()) {
                val feat   = upArr.getJSONObject(i)
                val props  = feat.optJSONObject("properties") ?: continue
                val geom   = feat.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue

                val mac  = props.optString("shortMac").ifEmpty { props.optString("mac", "?") }
                // OTM uses "traffic_light" (underscore) — normalise to "traffic-light"
                var kind = props.optString("kind", "unknown").replace('_', '-')
                // RSUs with a trafficLightName are also traffic lights
                if (kind == "rsu" && props.optString("trafficLightName").isNotEmpty()) {
                    kind = "traffic-light"
                }
                val speedKmh   = props.optDouble("speedKmh",   Double.NaN).takeUnless { it.isNaN() }
                val headingDeg = props.optDouble("headingDeg", Double.NaN).takeUnless { it.isNaN() }
                val spatData   = parseSpatData(props)
                upserts.add(OtmPoint(mac, kind, lat, lon, speedKmh, headingDeg,
                    spatPhase = spatData?.first, spatSecsLeft = spatData?.second))
            }
        }

        val delArr = obj.optJSONArray("deletePoints")
        if (delArr != null) {
            for (i in 0 until delArr.length()) deletes.add(delArr.optString(i))
        }

        if (upserts.isNotEmpty() || deletes.isNotEmpty()) {
            mainHandler.post { onPoints(upserts, deletes) }
        }
    }

    /**
     * Parses traffic-light-map-batch: static MAPEM intersection data.
     * Each entry has a MAC + map.refLat/refLon giving the intersection centre.
     * We create OtmPoint entries so Drive Mode can show the light even before
     * live SPAT data arrives.
     */
    private fun parseMapBatch(obj: JSONObject) {
        val entries = obj.optJSONArray("entries") ?: return
        val upserts = mutableListOf<OtmPoint>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val mac   = entry.optString("mac")
            if (mac.isEmpty()) continue
            val map   = entry.optJSONObject("map") ?: continue
            val lat   = map.optDouble("refLat", Double.NaN)
            val lon   = map.optDouble("refLon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            upserts.add(OtmPoint(mac, "traffic-light", lat, lon,
                speedKmh = null, headingDeg = null, spatPhase = null))
        }
        if (upserts.isNotEmpty()) {
            Log.d(tag, "MAPEM batch: ${upserts.size} intersections")
            mainHandler.post { onPoints(upserts, emptyList()) }
        }
    }

    /**
     * Returns (phase, secsLeft) from the trafficLightSpat property, or null if absent.
     *
     * minEndTime / timeStamp are DSRC TimeMarks expressed in milliseconds within the
     * current minute (0..59999). To get "seconds remaining":
     *   remaining = ((minEndTime - timeStamp + 60000) % 60000) / 1000
     */
    private fun parseSpatData(props: JSONObject): Pair<SpatTemParser.Phase, Int?>? {
        val spat   = props.optJSONObject("trafficLightSpat") ?: return null
        val groups = spat.optJSONArray("groups") ?: return null
        val tsMs   = spat.optInt("timeStamp", -1)   // ms within current minute

        var hasGreen = false; var hasYellow = false; var hasRed = false
        var greenSecs: Int? = null; var yellowSecs: Int? = null; var redSecs: Int? = null

        for (i in 0 until groups.length()) {
            val g     = groups.getJSONObject(i)
            val state = g.optInt("eventState", -1)
            val endMs = g.optInt("minEndTime", -1)

            // Calculate seconds remaining only when both timestamps are valid
            val secs: Int? = if (tsMs in 0..59999 && endMs in 0..59999) {
                val remainingMs = (endMs - tsMs + 60_000) % 60_000
                val s = remainingMs / 1000
                if (s in 1..120) s else null   // only show sensible 1–120 s values
            } else null

            when (state) {
                3 -> { hasRed    = true; if (secs != null) redSecs    = secs }
                5 -> { hasYellow = true; if (secs != null) yellowSecs = secs }
                6 -> { hasGreen  = true; if (secs != null) greenSecs  = secs }
            }
        }
        return when {
            hasGreen  -> SpatTemParser.Phase.GREEN  to greenSecs
            hasYellow -> SpatTemParser.Phase.YELLOW to yellowSecs
            hasRed    -> SpatTemParser.Phase.RED    to redSecs
            else      -> SpatTemParser.Phase.UNKNOWN to null
        }
    }

    private fun parseSpatPhase(props: JSONObject): SpatTemParser.Phase? = parseSpatData(props)?.first

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(tag, "connected to OTM")
            isConnected = true
            retryDelayMs = 2_000L
        }

        override fun onMessage(webSocket: WebSocket, text: String) = parse(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val text = GZIPInputStream(ByteArrayInputStream(bytes.toByteArray()))
                    .readBytes().toString(Charsets.UTF_8)
                parse(text)
            } catch (e: Exception) {
                Log.w(tag, "gzip decompress failed", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(tag, "closed: $code $reason")
            isConnected = false
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(tag, "ws failure", t)
            isConnected = false
            scheduleReconnect()
        }
    }

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
