import Foundation

/// Minimal UPER bit-reader for ETSI EN 302 637-3 DENM messages.
/// Extracts causeCode and subCauseCode from the SituationContainer.
/// Swift port of the Kotlin `DenmParser`.
public enum DenmParser {

    public struct Cause: Equatable, Sendable {
        public let causeCode: Int
        public let subCauseCode: Int

        public init(causeCode: Int, subCauseCode: Int) {
            self.causeCode = causeCode
            self.subCauseCode = subCauseCode
        }

        public func label() -> String { causeLabels[causeCode] ?? "Code \(causeCode)" }

        public func sublabel() -> String {
            switch causeCode {
            case 1:  return subTraffic[subCauseCode] ?? "/\(subCauseCode)"
            case 2:  return subAccident[subCauseCode] ?? "/\(subCauseCode)"
            case 14: return subSlow[subCauseCode] ?? "/\(subCauseCode)"
            case 91: return subBreakdown[subCauseCode] ?? "/\(subCauseCode)"
            case 94: return subStationary[subCauseCode] ?? "/\(subCauseCode)"
            default: return subCauseCode == 0 ? "" : "/\(subCauseCode)"
            }
        }
    }

    /// - Parameters:
    ///   - p: full 802.11 frame payload
    ///   - btpOff: byte offset of the 4-byte BTP-B header within `p`
    public static func extractCause(_ p: [UInt8], btpOff: Int) -> Cause? {
        let itsStart = btpOff + 4
        if itsStart + 6 > p.count { return nil }
        if Int(p[itsStart]) != 2 { return nil }        // protocolVersion
        if Int(p[itsStart + 1]) != 1 { return nil }    // messageID = DENM
        return parseDenmCause(BitReader(p, startByte: itsStart + 6))
    }

    private static func parseDenmCause(_ b: BitReader) -> Cause? {
        // DecentralizedEnvironmentalNotificationMessage preamble (4 bits)
        _ = b.bit()                    // extension flag
        let sitPresent = b.bit()
        _ = b.bit()                    // location present
        _ = b.bit()                    // alacarte present

        // ManagementContainer preamble: 5 optional/default field flags (no ext bit)
        let termPres     = b.bit()
        let relDistPres  = b.bit()
        let relDirPres   = b.bit()
        let valDurPres   = b.bit()
        let transIntPres = b.bit()

        // ActionID: originatingStationID(32) + sequenceNumber(16)
        _ = b.bits(48)

        // detectionTime + referenceTime: TimestampIts INTEGER(0..4398046511103) = 42 bits each
        _ = b.bits(84)

        // termination OPTIONAL: ENUMERATED{isCancellation, isNegation} = 1 bit
        if termPres == 1 { _ = b.bit() }

        // eventPosition = ReferencePosition (lat31 + lon32 + ellipse36 + alt20 + altConf4 = 123 bits)
        _ = b.bits(123)

        // relevanceDistance OPTIONAL: ENUM(8 non-ext values) = 3 bits
        if relDistPres == 1 { _ = b.bits(3) }

        // relevanceTrafficDirection OPTIONAL: ENUM(4 non-ext values) = 2 bits
        if relDirPres == 1 { _ = b.bits(2) }

        // validityDuration DEFAULT(600): INT(0..86400) = 17 bits
        if valDurPres == 1 { _ = b.bits(17) }

        // transmissionInterval OPTIONAL: INT(1..10000) = 14 bits
        if transIntPres == 1 { _ = b.bits(14) }

        // stationType: INT(0..255) = 8 bits
        _ = b.bits(8)

        if sitPresent == 0 { return nil }

        // SituationContainer preamble (extensible v1.3.x): ext_bit + 3 optional flags
        _ = b.bit()                    // extension bit
        let infoQPres = b.bit()
        _ = b.bit()                    // linkedCause present
        _ = b.bit()                    // eventHistory present

        // informationQuality OPTIONAL: INT(0..7) = 3 bits
        if infoQPres == 1 { _ = b.bits(3) }

        let causeCode    = b.bits(8)
        let subCauseCode = b.bits(8)
        return Cause(causeCode: causeCode, subCauseCode: subCauseCode)
    }

    // ── cause-code tables (ETSI EN 302 637-3 CauseCodeType) ─────────────────

    private static let causeLabels: [Int: String] = [
        1:  "Verkehrsbehinderung",
        2:  "Unfall",
        3:  "Baustelle",
        6:  "Gefährliche Fahrbahn",
        9:  "Hindernis",
        12: "Fußgänger auf Fahrbahn",
        14: "Geisterfahrer",
        15: "Rettungseinsatz",
        17: "Extremwetter",
        18: "Sichtbehinderung",
        19: "Starkregen/Schnee",
        26: "Langsamfahrer",
        27: "Stauende",
        91: "Fahrzeugpanne",
        92: "Nach Unfall",
        93: "Personenproblem",
        94: "Stehendes Fahrzeug",
        95: "Einsatzfahrzeug",
        96: "Gefährliche Kurve",
        97: "Kollisionsrisiko",
        98: "Rotlichtverstoß",
        99: "Gefährliche Situation",
    ]

    private static let subTraffic: [Int: String] = [
        0: "", 1: "(erhöhtes Aufkommen)", 2: "(Stau bildend)",
        3: "(Stau)", 4: "(dicker Stau)", 5: "(Stau stehend)", 6: "(stockend)",
    ]
    private static let subAccident: [Int: String] = [
        0: "", 1: "(ohne Einsatz)", 2: "(mit Einsatz)",
        3: "(mehrf. Unfälle)", 4: "(Hindernis)", 5: "(Aquaplaning)", 6: "(Eis)",
    ]
    private static let subSlow: [Int: String] = [
        0: "", 1: "(Gefahrgut)", 2: "(Fahrzeugpanne)",
        3: "(Baustelle)", 4: "(links überholen)",
    ]
    private static let subBreakdown: [Int: String] = [
        0: "", 1: "(ohne Gefahr)", 2: "(Panne m. Feuer)",
        3: "(Flüssigkeit)", 4: "(Gefahrgut)", 5: "(Unbekannt)",
    ]
    private static let subStationary: [Int: String] = [
        0: "", 1: "(m. Gefahren)", 2: "(Pannenhilfe)",
        3: "(Fahrzeugpanne)", 4: "(Unfall)", 5: "(Gefährl. Fahrzeug)",
    ]
}
