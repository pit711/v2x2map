package org.opentrafficmap.receiver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates realistic ITS-G5 demo frames that are treated identically to
 * hardware-received frames — recording, MQTT forwarding, log, geiger counter
 * all operate on them normally.
 *
 * Payloads are structurally valid 802.11p / GeoNetworking / BTP-B frames that
 * [ItsG5Decoder] can decode: correct LLC/SNAP magic, proper Source LPV with
 * encoded coordinates, and the right BTP destination port per message type.
 *
 * Station IDs carry [DEMO_STATION_PREFIX] in the high bytes so recordings can
 * be identified as synthetic.
 */
class DemoFrameGenerator {
    private var job: Job? = null
    private var centerLat = 51.0
    private var centerLon = 10.0
    private val vehicles = mutableListOf<SimVehicle>()
    private val rsus     = mutableListOf<SimRsu>()
    private var tickCount = 0L
    private val seqCounter = AtomicLong(DEMO_SEQ_START)

    private data class SimVehicle(
        var lat: Double, var lon: Double,
        var headingDeg: Double, var speedMps: Double,
        val stationId: Long,
        var turnRateDeg: Double = 0.0,
        var ticksUntilTurn: Int = 0,
    )

    private data class SimRsu(
        val lat: Double, val lon: Double,
        val stationId: Long,
        var phase: SpatTemParser.Phase = SpatTemParser.Phase.RED,
        var phaseTicksLeft: Int = 30,
    )

    fun setCenter(lat: Double, lon: Double) {
        centerLat = lat; centerLon = lon
        if (vehicles.isEmpty()) initEntities()
    }

    fun start(scope: CoroutineScope, onFrames: (List<Frame>) -> Unit) {
        if (vehicles.isEmpty()) initEntities()
        job = scope.launch {
            while (isActive) {
                onFrames(tick())
                delay(TICK_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun initEntities() {
        vehicles.clear(); rsus.clear()
        val cosLat = cos(Math.toRadians(centerLat))
        repeat(VEHICLE_COUNT) { idx ->
            val angle   = idx * (360.0 / VEHICLE_COUNT) + Random.nextDouble(-25.0, 25.0)
            val dist    = Random.nextDouble(60.0, 320.0)
            val dlatDeg = dist * cos(Math.toRadians(angle)) / 111_319.5
            val dlonDeg = dist * sin(Math.toRadians(angle)) / (111_319.5 * cosLat)
            vehicles.add(SimVehicle(
                lat        = centerLat + dlatDeg,
                lon        = centerLon + dlonDeg,
                headingDeg = (angle + 180.0) % 360.0,
                speedMps   = Random.nextDouble(4.0, 14.0),
                stationId  = DEMO_STATION_PREFIX or Random.nextLong(1L, 0x10000L),
            ))
        }
        repeat(2) { idx ->
            val angle   = idx * 180.0 + 45.0
            val dist    = 130.0
            val dlatDeg = dist * cos(Math.toRadians(angle)) / 111_319.5
            val dlonDeg = dist * sin(Math.toRadians(angle)) / (111_319.5 * cosLat)
            rsus.add(SimRsu(
                lat          = centerLat + dlatDeg,
                lon          = centerLon + dlonDeg,
                stationId    = DEMO_STATION_PREFIX or (0x0100L + idx),
                phase        = if (idx == 0) SpatTemParser.Phase.GREEN else SpatTemParser.Phase.RED,
                phaseTicksLeft = if (idx == 0) 60 else 40,
            ))
        }
    }

    private fun tick(): List<Frame> {
        tickCount++
        val now    = System.currentTimeMillis()
        val frames = mutableListOf<Frame>()

        for (v in vehicles) { moveVehicle(v); frames.add(camFrame(v, now)) }
        for (r in rsus)     { advancePhase(r); frames.add(spatFrame(r, now)) }

        if (tickCount % 50L == 0L && vehicles.isNotEmpty()) {
            frames.add(denmFrame(vehicles.random(), now))
        }
        return frames
    }

    private fun moveVehicle(v: SimVehicle) {
        val dt = TICK_MS / 1000.0
        if (v.ticksUntilTurn <= 0) {
            v.turnRateDeg     = Random.nextDouble(-10.0, 10.0)
            v.ticksUntilTurn  = Random.nextInt(8, 35)
        }
        v.ticksUntilTurn--
        v.headingDeg = (v.headingDeg + v.turnRateDeg * dt + 360.0) % 360.0
        if (Random.nextInt(25) == 0)
            v.speedMps = (v.speedMps + Random.nextDouble(-2.5, 2.5)).coerceIn(2.0, 16.0)

        val hr   = Math.toRadians(v.headingDeg)
        val dist = v.speedMps * dt
        v.lat += dist * cos(hr) / 111_319.5
        v.lon += dist * sin(hr) / (111_319.5 * cos(Math.toRadians(v.lat)))

        // Redirect toward center if wandering too far (>440 m)
        val dlat = v.lat - centerLat
        val dlon = v.lon - centerLon
        val distM = sqrt(
            (dlat * 111_319.5).pow(2) + (dlon * 111_319.5 * cos(Math.toRadians(centerLat))).pow(2)
        )
        if (distM > 440.0) {
            v.headingDeg  = (Math.toDegrees(atan2(-dlon, -dlat)) + 360.0) % 360.0
            v.turnRateDeg = 0.0
        }
    }

    private fun advancePhase(r: SimRsu) {
        if (--r.phaseTicksLeft > 0) return
        r.phase = when (r.phase) {
            SpatTemParser.Phase.GREEN  -> { r.phaseTicksLeft = 10;  SpatTemParser.Phase.YELLOW }
            SpatTemParser.Phase.YELLOW -> { r.phaseTicksLeft = 50;  SpatTemParser.Phase.RED    }
            else                       -> { r.phaseTicksLeft = 60;  SpatTemParser.Phase.GREEN  }
        }
    }

    private fun camFrame(v: SimVehicle, nowMs: Long) = Frame(
        seq         = seqCounter.getAndIncrement(),
        sec         = nowMs / 1000,
        usec        = (nowMs % 1000) * 1000,
        payload     = buildPayload(BTP_CAM,    v.lat, v.lon, v.speedMps, v.headingDeg, v.stationId, 5),
        etherType   = ETHERTYPE_ITSG5,
        msgType     = ItsG5Decoder.MsgType.CAM,
        stationId   = v.stationId,
        stationType = 5,
        latLon      = v.lat to v.lon,
        headingDeg  = v.headingDeg,
        speedMps    = v.speedMps,
        secured     = false,
    )

    private fun spatFrame(r: SimRsu, nowMs: Long) = Frame(
        seq         = seqCounter.getAndIncrement(),
        sec         = nowMs / 1000,
        usec        = (nowMs % 1000) * 1000,
        payload     = buildPayload(BTP_SPATEM, r.lat, r.lon, 0.0, 0.0, r.stationId, 15),
        etherType   = ETHERTYPE_ITSG5,
        msgType     = ItsG5Decoder.MsgType.SPATEM,
        stationId   = r.stationId,
        stationType = 15,
        latLon      = r.lat to r.lon,
        headingDeg  = null,
        speedMps    = 0.0,
        spatPhase   = r.phase,
        secured     = false,
    )

    private fun denmFrame(v: SimVehicle, nowMs: Long) = Frame(
        seq         = seqCounter.getAndIncrement(),
        sec         = nowMs / 1000,
        usec        = (nowMs % 1000) * 1000,
        payload     = buildPayload(BTP_DENM,   v.lat, v.lon, v.speedMps, v.headingDeg, v.stationId or 0xF000L, 5),
        etherType   = ETHERTYPE_ITSG5,
        msgType     = ItsG5Decoder.MsgType.DENM,
        stationId   = v.stationId or 0xF000L,
        stationType = 5,
        latLon      = v.lat to v.lon,
        headingDeg  = v.headingDeg,
        speedMps    = v.speedMps,
        secured     = false,
    )

    /**
     * Builds a structurally valid ITS-G5 IEEE 802.11p frame that [ItsG5Decoder] fully decodes:
     *
     *   802.11 header (24 B) + LLC/SNAP (8 B) + GN Basic (4 B) + GN Common (8 B)
     *   + SHB extended header with Source LPV (28 B) + BTP-B (4 B) + app stub (10 B)
     *
     * Total = 86 bytes. Coordinates, speed and heading are encoded in the LPV.
     */
    private fun buildPayload(
        btpPort: Int, lat: Double, lon: Double,
        speedMps: Double, headingDeg: Double,
        stationId: Long, stationType: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(90)

        // 802.11 data frame header (24 bytes)
        val mac = ByteArray(24)
        mac[0] = 0x08; mac[1] = 0x00
        for (i in 4..9)   mac[i] = 0xFF.toByte()             // DA broadcast
        for (i in 0..5)   mac[10 + i] = ((stationId ushr ((5 - i) * 8)) and 0xFF).toByte()
        for (i in 16..21) mac[i] = 0xFF.toByte()             // BSSID broadcast
        out.write(mac)

        // LLC/SNAP  AA AA 03 00 00 00 89 47
        out.write(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x89.toByte(), 0x47.toByte()))

        // GN Basic Header: version=1, NH=1 (common hdr next), reserved, HL=1
        out.write(byteArrayOf(0x11, 0x00, 0x00, 0x01))

        // GN Common Header: NH=2(BTP-B), HT=5(SHB), HST=0, payloadLen = SHB(28)+BTP(4)+app(10)
        val payloadLen = 42
        out.write(byteArrayOf(0x02, 0x50.toByte(), 0x00, 0x00,
            (payloadLen ushr 8).toByte(), payloadLen.toByte(), 0x01, 0x00))

        // SHB Extended Header — Source LPV (24 B) + reserved (4 B)
        //   GN_ADDR (8 B): [M=0 | stationType(5) | res(2)] [res] [6-byte station MAC]
        out.write(((stationType and 0x1F) shl 2))
        out.write(0)
        for (i in 0..5) out.write(((stationId ushr ((5 - i) * 8)) and 0xFF).toInt())
        //   TST (4 B)
        writeBeInt(out, (System.currentTimeMillis() and 0xFFFFFFFFL).toInt())
        //   LAT, LON in 1/10 µdeg
        writeBeInt(out, (lat * 1e7).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        writeBeInt(out, (lon * 1e7).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        //   SPD (15 b, 0.01 m/s) | HDG (16 b, 0.1 deg) packed in 4 bytes
        val speedRaw = (speedMps * 100.0).toInt().coerceIn(0, 0x7FFE)
        val hdgRaw   = (headingDeg * 10.0).toInt() and 0xFFFF
        writeBeInt(out, (speedRaw shl 16) or hdgRaw)
        //   reserved (4 B)
        out.write(ByteArray(4))

        // BTP-B header: destination port + port info (4 B)
        out.write((btpPort ushr 8) and 0xFF)
        out.write(btpPort and 0xFF)
        out.write(0); out.write(0)

        // Minimal application payload (10 B zeros)
        out.write(ByteArray(10))

        return out.toByteArray()
    }

    private fun writeBeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    companion object {
        private const val TICK_MS          = 300L
        private const val VEHICLE_COUNT    = 7
        private const val ETHERTYPE_ITSG5  = 0x8947
        private const val BTP_CAM          = 2001
        private const val BTP_DENM         = 2002
        private const val BTP_SPATEM       = 2004
        // High-byte prefix marks station IDs as synthetic (0x00FF...)
        val DEMO_STATION_PREFIX            = 0x00FF_0000_0000L
        // Demo frame sequence numbers start far above real hardware range
        private const val DEMO_SEQ_START   = 0x0000_FFFF_0000L
    }
}
