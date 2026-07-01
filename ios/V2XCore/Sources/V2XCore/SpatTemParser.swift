import Foundation

/// Minimal UPER decoder for ETSI EN 302 637-3 SPATEM messages.
/// Only handles unsecured payloads (protocolVersion byte = 0x02). Extracts the
/// MovementPhaseState of the first movement event of the first intersection.
/// Swift port of the Kotlin `SpatTemParser`.
public enum SpatTemParser {

    public enum Phase: String, Sendable {
        case red, yellow, green, unknown
    }

    /// - Parameters:
    ///   - p: full 802.11 frame payload (same array fed to `ItsG5Decoder`)
    ///   - btpOff: byte offset of the BTP-B header within `p`
    public static func extractPhase(_ p: [UInt8], btpOff: Int) -> Phase {
        let itsStart = btpOff + 4
        if itsStart + 6 > p.count { return .unknown }
        if Int(p[itsStart]) != 2 { return .unknown }       // not unsecured v2
        if Int(p[itsStart + 1]) != 4 { return .unknown }   // not SPATEM
        return parseSpatPhase(BitReader(p, startByte: itsStart + 6))
    }

    private static func parseSpatPhase(_ b: BitReader) -> Phase {
        // SPAT ::= SEQUENCE { timeStamp OPT(20b), name OPT(var), intersections, ... }
        _ = b.bit()
        let spatOpt = b.bits(2)
        if (spatOpt >> 1) & 1 == 1 { _ = b.bits(20) }
        if spatOpt & 1 == 1 { _ = b.bits(b.constrained(1, 63) * 8) }

        _ = b.constrained(1, 32) // IntersectionStateList count

        // IntersectionState (first)
        _ = b.bit()              // ext
        let intOpt = b.bits(6)   // name, moy, ts, lanes, maneuvers, regional
        if (intOpt >> 5) & 1 == 1 { _ = b.bits(b.constrained(1, 63) * 8) } // name

        // IntersectionReferenceID ::= SEQUENCE { region OPT, id }
        if b.bit() == 1 { _ = b.bits(16) } // region INTEGER(0..65535)
        _ = b.bits(16) // id

        _ = b.bits(7)  // revision INTEGER(0..127)
        _ = b.bits(16) // status BIT STRING(16)

        if (intOpt >> 4) & 1 == 1 { _ = b.bits(20) } // moy MinuteOfTheYear
        if (intOpt >> 3) & 1 == 1 { _ = b.bits(16) } // timeStamp DSecond
        if (intOpt >> 2) & 1 == 1 {                  // enabledLanes
            let n = b.constrained(1, 16)
            for _ in 0..<n { _ = b.bits(8) }
        }

        _ = b.constrained(1, 255) // MovementList count

        // MovementState (first)
        _ = b.bit()              // ext
        let movOpt = b.bits(3)   // name, maneuvers, regional
        if (movOpt >> 2) & 1 == 1 { _ = b.bits(b.constrained(1, 63) * 8) } // name
        _ = b.bits(8) // signalGroup INTEGER(0..255)

        _ = b.constrained(1, 16) // MovementEventList count

        // MovementEvent (first)
        _ = b.bit()    // ext
        _ = b.bits(3)  // optional bitmap: timing, speeds, regional

        // eventState MovementPhaseState ENUMERATED(9 values, 0..8) → 4 bits
        switch b.constrained(0, 8) {
        case 3:    return .red
        case 7, 8: return .yellow
        case 5, 6: return .green
        default:   return .unknown
        }
    }
}
