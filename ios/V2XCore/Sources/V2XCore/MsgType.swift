import Foundation

/// ITS message type, derived from the BTP destination port.
/// Swift port of `ItsG5Decoder.MsgType` (Kotlin). Colors match the Android marker tints.
public enum MsgType: String, CaseIterable, Sendable {
    case unknown, cam, denm, mapem, spatem, ivim, srem, ssem, tlm, rtcmem

    public var short: String {
        switch self {
        case .unknown: return "?"
        case .cam:     return "CAM"
        case .denm:    return "DENM"
        case .mapem:   return "MAPEM"
        case .spatem:  return "SPATEM"
        case .ivim:    return "IVIM"
        case .srem:    return "SREM"
        case .ssem:    return "SSEM"
        case .tlm:     return "TLM"
        case .rtcmem:  return "RTCMEM"
        }
    }

    /// ARGB color (matches the Android `MsgType.color` values).
    public var colorARGB: UInt32 {
        switch self {
        case .unknown: return 0xFF60_7D8B
        case .cam:     return 0xFF19_76D2
        case .denm:    return 0xFFE6_5100
        case .mapem:   return 0xFF7B_1FA2
        case .spatem:  return 0xFF38_8E3C
        case .ivim:    return 0xFFC2_185B
        case .srem:    return 0xFF00_838F
        case .ssem:    return 0xFF00_838F
        case .tlm:     return 0xFFAF_B42B
        case .rtcmem:  return 0xFF45_5A64
        }
    }

    public var label: String {
        switch self {
        case .unknown: return "? – Unbekannt / Unknown"
        case .cam:     return "CAM – Fahrzeugposition / Vehicle position"
        case .denm:    return "DENM – Gefahrenmeldung / Hazard warning"
        case .mapem:   return "MAPEM – Kreuzungsgeometrie / Intersection map"
        case .spatem:  return "SPATEM – Ampelphase / Signal phase & timing"
        case .ivim:    return "IVIM – Fahrzeuginfo / In-vehicle info"
        case .srem:    return "SREM – Signalanfrage / Signal request"
        case .ssem:    return "SSEM – Signalstatus / Signal status"
        case .tlm:     return "TLM – Verkehrslicht / Traffic light"
        case .rtcmem:  return "RTCMEM – Korrekturdaten / Correction data"
        }
    }

    public static func fromBtpPort(_ port: Int) -> MsgType {
        switch port {
        case 2001: return .cam
        case 2002: return .denm
        case 2003: return .mapem
        case 2004: return .spatem
        case 2006: return .ivim
        case 2007: return .srem
        case 2008: return .ssem
        case 2010: return .tlm
        case 2012: return .rtcmem
        default:   return .unknown
        }
    }
}
