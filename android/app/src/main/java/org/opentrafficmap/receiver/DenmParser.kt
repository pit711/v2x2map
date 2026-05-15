package org.opentrafficmap.receiver

/**
 * Minimal UPER bit-reader for ETSI EN 302 637-3 DENM messages.
 *
 * Extracts causeCode and subCauseCode from the SituationContainer.
 * Assumes the common European schema (EN 302 637-3 v1.3.x) with:
 *   – ManagementContainer NOT extensible (no extension bit, 5 optional-field flags)
 *   – SituationContainer extensible (1 extension bit + 3 optional flags)
 *   – AltitudeValue INT(-100000..800001) = 20 bits
 *   – RelevanceDistance ENUM(8) = 3 bits, RelevanceTrafficDirection ENUM(4) = 2 bits
 */
object DenmParser {

    data class Cause(val causeCode: Int, val subCauseCode: Int) {
        fun label(): String = CAUSE_LABELS[causeCode] ?: "Code $causeCode"
        fun sublabel(): String = when (causeCode) {
            1  -> SUB_TRAFFIC[subCauseCode]
            2  -> SUB_ACCIDENT[subCauseCode]
            14 -> SUB_SLOW[subCauseCode]
            91 -> SUB_BREAKDOWN[subCauseCode]
            94 -> SUB_STATIONARY[subCauseCode]
            else -> if (subCauseCode == 0) "" else "/$subCauseCode"
        } ?: "/$subCauseCode"
    }

    /**
     * @param p      full 802.11 frame payload
     * @param btpOff byte offset of the 4-byte BTP-B header within [p]
     */
    fun extractCause(p: ByteArray, btpOff: Int): Cause? {
        val itsStart = btpOff + 4
        if (itsStart + 6 > p.size) return null
        if ((p[itsStart].toInt() and 0xFF) != 2) return null   // protocolVersion
        if ((p[itsStart + 1].toInt() and 0xFF) != 1) return null // messageID = DENM
        return try {
            parseDenmCause(Bits(p, itsStart + 6))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDenmCause(b: Bits): Cause? {
        // DecentralizedEnvironmentalNotificationMessage preamble (4 bits)
        b.bit()                       // extension flag
        val sitPresent = b.bit()
        b.bit()                       // location present
        b.bit()                       // alacarte present

        // ManagementContainer preamble: 5 optional/default field flags (no ext bit)
        val termPres    = b.bit()
        val relDistPres = b.bit()
        val relDirPres  = b.bit()
        val valDurPres  = b.bit()
        val transIntPres= b.bit()

        // ActionID: originatingStationID(32) + sequenceNumber(16)
        b.bits(48)

        // detectionTime + referenceTime: TimestampIts INTEGER(0..4398046511103) = 42 bits each
        b.bits(84)

        // termination OPTIONAL: ENUMERATED{isCancellation, isNegation} = 1 bit
        if (termPres == 1) b.bit()

        // eventPosition = ReferencePosition:
        //   latitude  INT(-900000000..900000000) = 31 bits
        //   longitude INT(-1800000000..1800000000) = 32 bits
        //   PosConfidenceEllipse: 3 × SemiAxisLength/HeadingValue(12b each) = 36 bits
        //   AltitudeValue INT(-100000..800001) = 20 bits
        //   AltitudeConfidence ENUMERATED(16 values) = 4 bits
        b.bits(123)

        // relevanceDistance OPTIONAL: ENUM(8 non-ext values) = 3 bits
        if (relDistPres == 1) b.bits(3)

        // relevanceTrafficDirection OPTIONAL: ENUM(4 non-ext values) = 2 bits
        if (relDirPres == 1) b.bits(2)

        // validityDuration DEFAULT(600): INT(0..86400) = 17 bits
        if (valDurPres == 1) b.bits(17)

        // transmissionInterval OPTIONAL: INT(1..10000) = 14 bits
        if (transIntPres == 1) b.bits(14)

        // stationType: INT(0..255) = 8 bits
        b.bits(8)

        if (sitPresent == 0) return null

        // SituationContainer preamble (extensible v1.3.x): ext_bit + 3 optional flags
        b.bit()                        // extension bit
        val infoQPres  = b.bit()
        b.bit()                        // linkedCause present
        b.bit()                        // eventHistory present

        // informationQuality OPTIONAL: INT(0..7) = 3 bits
        if (infoQPres == 1) b.bits(3)

        val causeCode    = b.bits(8)
        val subCauseCode = b.bits(8)
        return Cause(causeCode, subCauseCode)
    }

    private class Bits(val b: ByteArray, startByte: Int) {
        var pos = startByte * 8

        fun bit(): Int {
            if (pos / 8 >= b.size) return 0
            return ((b[pos / 8].toInt() ushr (7 - pos % 8)) and 1).also { pos++ }
        }

        fun bits(n: Int): Int = (0 until n).fold(0) { acc, _ -> (acc shl 1) or bit() }
    }

    // ── cause-code tables (ETSI EN 302 637-3 CauseCodeType) ─────────────────

    private val CAUSE_LABELS = mapOf(
        1  to "Verkehrsbehinderung",
        2  to "Unfall",
        3  to "Baustelle",
        6  to "Gefährliche Fahrbahn",
        9  to "Hindernis",
        12 to "Fußgänger auf Fahrbahn",
        14 to "Geisterfahrer",
        15 to "Rettungseinsatz",
        17 to "Extremwetter",
        18 to "Sichtbehinderung",
        19 to "Starkregen/Schnee",
        26 to "Langsamfahrer",
        27 to "Stauende",
        91 to "Fahrzeugpanne",
        92 to "Nach Unfall",
        93 to "Personenproblem",
        94 to "Stehendes Fahrzeug",
        95 to "Einsatzfahrzeug",
        96 to "Gefährliche Kurve",
        97 to "Kollisionsrisiko",
        98 to "Rotlichtverstoß",
        99 to "Gefährliche Situation",
    )

    private val SUB_TRAFFIC = mapOf(
        0 to "", 1 to "(erhöhtes Aufkommen)", 2 to "(Stau bildend)",
        3 to "(Stau)", 4 to "(dicker Stau)", 5 to "(Stau stehend)", 6 to "(stockend)"
    )
    private val SUB_ACCIDENT = mapOf(
        0 to "", 1 to "(ohne Einsatz)", 2 to "(mit Einsatz)",
        3 to "(mehrf. Unfälle)", 4 to "(Hindernis)", 5 to "(Aquaplaning)", 6 to "(Eis)"
    )
    private val SUB_SLOW = mapOf(
        0 to "", 1 to "(Gefahrgut)", 2 to "(Fahrzeugpanne)",
        3 to "(Baustelle)", 4 to "(links überholen)"
    )
    private val SUB_BREAKDOWN = mapOf(
        0 to "", 1 to "(ohne Gefahr)", 2 to "(Panne m. Feuer)",
        3 to "(Flüssigkeit)", 4 to "(Gefahrgut)", 5 to "(Unbekannt)"
    )
    private val SUB_STATIONARY = mapOf(
        0 to "", 1 to "(m. Gefahren)", 2 to "(Pannenhilfe)",
        3 to "(Fahrzeugpanne)", 4 to "(Unfall)", 5 to "(Gefährl. Fahrzeug)"
    )
}
