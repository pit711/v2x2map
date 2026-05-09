package org.opentrafficmap.receiver

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IEEE 802.11p / LLC-SNAP / GeoNetworking / BTP decoder.
 *
 * The on-air structure of an ITS-G5 frame is:
 *
 *   [802.11 MAC header   24 / 26 / 30 / 32 B]   ← header length varies with QoS, addr4, …
 *   [LLC/SNAP             8 B: AA AA 03 00 00 00 ET_HI ET_LO]
 *   [GN Basic Header      4 B]
 *   [GN Common Header     8 B]                  ← byte 1 holds HT (high nibble) + HST
 *   [GN Extended Header   N B]                  ← length depends on HT (SHB=28, GBC=44, …)
 *   [BTP-B header         4 B: dstPort | dstPortInfo]
 *   [ASN.1-encoded payload]
 *
 * We don't run a full ASN.1 decoder — instead we lean on the cheap signals:
 *   • BTP destination port → message type (CAM / DENM / SPATEM / MAPEM / …)
 *   • GeoNetworking Source Long Position Vector → lat/lon, heading, speed
 *   • GeoNetworking Source Address → 4-byte station ID
 *
 * This gets us 90 % of the visualisation value for ~5 % of the work.
 */
object ItsG5Decoder {

    private const val ETHERTYPE_ITSG5 = 0x8947

    /** Backwards-compat shim — older callers only need EtherType + lat/lon. */
    fun decode(payload: ByteArray): Pair<Int?, Pair<Double, Double>?> {
        val full = decodeFull(payload)
        return full.etherType to full.latLon
    }

    fun decodeFull(p: ByteArray): Decoded {
        val et = sniffEtherType(p)
        if (et == null || et != ETHERTYPE_ITSG5) {
            return Decoded(etherType = et)
        }
        for (hdrLen in HDR_LENGTHS) {
            // Need at least 802.11 + LLC/SNAP + Basic + Common to read HT.
            if (p.size < hdrLen + 8 + 4 + 8) continue

            // Verify LLC/SNAP magic — rejects wrong header-length guesses (e.g. hdrLen=24
            // on a QoS-Data frame whose actual header is 26 bytes).
            if (p[hdrLen + 0] != 0xAA.toByte()
                || p[hdrLen + 1] != 0xAA.toByte()
                || p[hdrLen + 2] != 0x03.toByte()
                || p[hdrLen + 3] != 0x00.toByte()
                || p[hdrLen + 4] != 0x00.toByte()
                || p[hdrLen + 5] != 0x00.toByte()
            ) continue

            val basicOff  = hdrLen + 8
            val commonOff = basicOff + 4
            val htHst = p[commonOff + 1].toInt() and 0xFF
            val ht    = (htHst shr 4) and 0x0F
            val hst   = htHst and 0x0F

            val extHdrLen = when (ht) {
                1    ->  4    // BEACON       (no real ext hdr but Beacon hdr ~4 B)
                2    -> 48    // GUC
                3    -> 56    // GAC
                4    -> 44    // GBC          (Source LongPV 24 + GeoArea 16 + reserved 4)
                5    -> 28    // SHB / TSB    (Source LongPV 24 + reserved 4)
                6    -> 36    // LS           (LS request)
                else -> 28    // best-effort fallback (most common for V2X)
            }

            // SHB (ht=5, hst=0) puts the Source LPV directly at the start of the
            // extended header.  Every other type (GBC, GAC, GUC, TSB) prepends a
            // 2-byte sequence number + 2-byte reserved field before the LPV.
            val srcPosInExt = if (ht == 5 && hst == 0) 0 else 4
            val srcPosOff   = commonOff + 8 + srcPosInExt
            val btpOff      = commonOff + 8 + extHdrLen
            if (p.size < btpOff + 4) continue

            // Source Long Position Vector layout (24 B):
            //   GnAddress  8 B
            //     bit 0    M-flag
            //     bits 1-7 station-type
            //     bytes 2..7 LL-MAC of the originator (6 B)
            //   timestamp  4 B
            //   latitude   4 B  (int32 BE, 1/10 µdeg)
            //   longitude  4 B  (int32 BE, 1/10 µdeg)
            //   PAI(1) | speed(15)  2 B BE
            //   heading(16)         2 B BE
            val gnAddrHi = ((p[srcPosOff + 0].toInt() and 0xFF) shl 24) or
                           ((p[srcPosOff + 1].toInt() and 0xFF) shl 16) or
                           ((p[srcPosOff + 2].toInt() and 0xFF) shl  8) or
                           (p[srcPosOff + 3].toInt() and 0xFF)
            val gnAddrLo = ((p[srcPosOff + 4].toInt() and 0xFF) shl 24) or
                           ((p[srcPosOff + 5].toInt() and 0xFF) shl 16) or
                           ((p[srcPosOff + 6].toInt() and 0xFF) shl  8) or
                           (p[srcPosOff + 7].toInt() and 0xFF)
            // station-id is whole 8-byte addr packed into a Long
            val stationId = ((gnAddrHi.toLong() and 0xFFFFFFFFL) shl 32) or
                            (gnAddrLo.toLong() and 0xFFFFFFFFL)

            // Standard LPV layout: GN_ADDR(8) + TST(4) + LAT(4) + LON(4) + PAI|SPD(2) + HDG(2)
            // Some RSU implementations use an 8-byte TAI timestamp instead of the standard 4-byte,
            // shifting lat/lon 4 bytes later. If the standard offsets produce an impossible
            // coordinate (|lat|>90), try the shifted offsets before giving up.
            var latRaw = readBeI32(p, srcPosOff + 12)
            var lonRaw = readBeI32(p, srcPosOff + 16)
            var pshOff = srcPosOff + 20
            if ((latRaw / 1e7) !in -90.0..90.0 && p.size >= srcPosOff + 28) {
                val latShifted = readBeI32(p, srcPosOff + 16)
                val lonShifted = readBeI32(p, srcPosOff + 20)
                if ((latShifted / 1e7) in -90.0..90.0 && (lonShifted / 1e7) in -180.0..180.0) {
                    latRaw = latShifted
                    lonRaw = lonShifted
                    pshOff = srcPosOff + 24
                }
            }
            val pshRaw = readBeI32(p, pshOff)
            // top bit = position-accuracy-indicator, bits 1..15 = speed (signed 0.01 m/s),
            // bits 16..31 = heading (unsigned 0.1 deg).
            val speedRaw   = (pshRaw shr 16) and 0x7FFF
            val speedSigned = if (speedRaw >= 0x4000) speedRaw - 0x8000 else speedRaw
            val headingRaw  = pshRaw and 0xFFFF
            val speed   = speedSigned / 100.0
            val heading = headingRaw / 10.0

            val lat = latRaw / 1e7
            val lon = lonRaw / 1e7
            val latLon: Pair<Double, Double>? =
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0))
                    lat to lon else null

            val dstPort = ((p[btpOff].toInt() and 0xFF) shl 8) or
                          (p[btpOff + 1].toInt() and 0xFF)
            val msgType = MsgType.fromBtpPort(dstPort)
            val spatPhase = if (msgType == MsgType.SPATEM)
                SpatTemParser.extractPhase(p, btpOff) else null

            return Decoded(
                etherType  = et,
                msgType    = msgType,
                stationId  = stationId,
                latLon     = latLon,
                headingDeg = if (latLon != null && headingRaw != 0xFFFF) heading else null,
                speedMps   = if (latLon != null) speed else null,
                btpDstPort = dstPort,
                spatPhase  = spatPhase,
            )
        }
        return Decoded(etherType = et)
    }

    fun sniffEtherType(p: ByteArray): Int? {
        for (hdrLen in HDR_LENGTHS) {
            if (p.size < hdrLen + 8) continue
            if (p[hdrLen + 0] == 0xAA.toByte()
                && p[hdrLen + 1] == 0xAA.toByte()
                && p[hdrLen + 2] == 0x03.toByte()
                && p[hdrLen + 3] == 0x00.toByte()
                && p[hdrLen + 4] == 0x00.toByte()
                && p[hdrLen + 5] == 0x00.toByte()
            ) {
                val et = ((p[hdrLen + 6].toInt() and 0xFF) shl 8) or (p[hdrLen + 7].toInt() and 0xFF)
                return et
            }
        }
        return null
    }

    private fun readBeI32(p: ByteArray, off: Int): Int =
        ByteBuffer.wrap(p, off, 4).order(ByteOrder.BIG_ENDIAN).int

    private val HDR_LENGTHS = intArrayOf(24, 26, 30, 32)

    // ---- public types -------------------------------------------------

    data class Decoded(
        val etherType: Int? = null,
        val msgType: MsgType = MsgType.UNKNOWN,
        val stationId: Long? = null,
        val latLon: Pair<Double, Double>? = null,
        val headingDeg: Double? = null,
        val speedMps: Double? = null,
        val btpDstPort: Int? = null,
        val spatPhase: SpatTemParser.Phase? = null,
    )

    /** ITS message type. Colors are M3-friendly tones the Marker drawables tint to. */
    enum class MsgType(val short: String, val color: Int) {
        UNKNOWN ("?",       0xFF607D8B.toInt()),  // blue grey
        CAM     ("CAM",     0xFF1976D2.toInt()),  // blue            — vehicle awareness
        DENM    ("DENM",    0xFFE65100.toInt()),  // orange          — hazard
        MAPEM   ("MAPEM",   0xFF7B1FA2.toInt()),  // purple          — intersection geometry
        SPATEM  ("SPATEM",  0xFF388E3C.toInt()),  // green           — signal phase + timing
        IVIM    ("IVIM",    0xFFC2185B.toInt()),  // pink            — in-vehicle info
        SREM    ("SREM",    0xFF00838F.toInt()),  // teal            — signal request
        SSEM    ("SSEM",    0xFF00838F.toInt()),  // teal            — signal status
        TLM     ("TLM",     0xFFAFB42B.toInt()),  // lime
        RTCMEM  ("RTCMEM",  0xFF455A64.toInt()),  // dark grey
        ;
        companion object {
            fun fromBtpPort(port: Int): MsgType = when (port) {
                2001 -> CAM
                2002 -> DENM
                2003 -> MAPEM
                2004 -> SPATEM
                2006 -> IVIM
                2007 -> SREM
                2008 -> SSEM
                2010 -> TLM
                2012 -> RTCMEM
                else -> UNKNOWN
            }
        }
    }
}
