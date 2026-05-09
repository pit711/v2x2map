package org.opentrafficmap.receiver

/**
 * Minimal UPER decoder for ETSI EN 302 637-3 SPATEM messages.
 *
 * Only handles unsecured (non-IEEE-1609.2-wrapped) SPATEM payloads
 * (protocolVersion byte = 0x02). Extracts the MovementPhaseState from
 * the first movement event of the first intersection.
 *
 * Returns UNKNOWN for secured frames, malformed data, or any parse error.
 */
object SpatTemParser {

    enum class Phase { RED, YELLOW, GREEN, UNKNOWN }

    private class Bits(val b: ByteArray, startByte: Int) {
        var pos = startByte * 8

        fun bit(): Int {
            if (pos / 8 >= b.size) return 0
            return ((b[pos / 8].toInt() ushr (7 - pos % 8)) and 1).also { pos++ }
        }

        fun bits(n: Int): Int = (0 until n).fold(0) { acc, _ -> (acc shl 1) or bit() }

        // UPER constrained whole number: value encoded in ceil(log2(range)) bits.
        fun constrained(lo: Int, hi: Int): Int {
            val range = hi - lo + 1
            if (range <= 1) return lo
            val nbits = 32 - Integer.numberOfLeadingZeros(range - 1)
            return lo + bits(nbits)
        }
    }

    /**
     * @param p      full 802.11 frame payload (same array fed to ItsG5Decoder)
     * @param btpOff byte offset of the BTP-B header within [p]
     */
    fun extractPhase(p: ByteArray, btpOff: Int): Phase {
        val itsStart = btpOff + 4
        if (itsStart + 6 > p.size) return Phase.UNKNOWN
        if (p[itsStart].toInt() and 0xFF != 2) return Phase.UNKNOWN   // not unsecured v2
        if (p[itsStart + 1].toInt() and 0xFF != 4) return Phase.UNKNOWN // not SPATEM
        return try {
            parseSpatPhase(Bits(p, itsStart + 6))
        } catch (_: Exception) {
            Phase.UNKNOWN
        }
    }

    private fun parseSpatPhase(b: Bits): Phase {
        // SPAT ::= SEQUENCE { timeStamp OPT(20b), name OPT(var), intersections, ... }
        b.bit()
        val spatOpt = b.bits(2)
        if ((spatOpt shr 1) and 1 == 1) b.bits(20)
        if (spatOpt and 1 == 1) b.bits(b.constrained(1, 63) * 8)

        b.constrained(1, 32) // IntersectionStateList count

        // IntersectionState (first)
        b.bit() // ext
        val intOpt = b.bits(6) // name, moy, ts, lanes, maneuvers, regional
        if ((intOpt shr 5) and 1 == 1) b.bits(b.constrained(1, 63) * 8) // name

        // IntersectionReferenceID ::= SEQUENCE { region OPT, id }
        if (b.bit() == 1) b.bits(16) // region INTEGER(0..65535)
        b.bits(16) // id

        b.bits(7)  // revision INTEGER(0..127)
        b.bits(16) // status BIT STRING(16)

        if ((intOpt shr 4) and 1 == 1) b.bits(20) // moy MinuteOfTheYear
        if ((intOpt shr 3) and 1 == 1) b.bits(16) // timeStamp DSecond
        if ((intOpt shr 2) and 1 == 1) {           // enabledLanes
            val n = b.constrained(1, 16)
            repeat(n) { b.bits(8) }
        }

        b.constrained(1, 255) // MovementList count

        // MovementState (first)
        b.bit() // ext
        val movOpt = b.bits(3) // name, maneuvers, regional
        if ((movOpt shr 2) and 1 == 1) b.bits(b.constrained(1, 63) * 8) // name
        b.bits(8) // signalGroup INTEGER(0..255)

        b.constrained(1, 16) // MovementEventList count

        // MovementEvent (first)
        b.bit()    // ext
        b.bits(3)  // optional bitmap: timing, speeds, regional

        // eventState MovementPhaseState ENUMERATED(9 values, 0..8) → 4 bits
        return when (b.constrained(0, 8)) {
            3    -> Phase.RED
            7, 8 -> Phase.YELLOW
            5, 6 -> Phase.GREEN
            else -> Phase.UNKNOWN
        }
    }
}
