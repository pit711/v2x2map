import Foundation
import SwiftUI

/// Receiver-cycle presets — the only way the UI lets the user tune the duty cycle.
/// Every preset is validated BLE-safe (a hardware sweep found the connected-sniff
/// stability cliff at ~700 ms and fastest connect at discSniff/discBle = 1000/1000).
/// `connSniff` is the data↔stability knob; disc values are fixed for fast connect.
enum CycleProfile: String, CaseIterable, Identifiable {
    case stabil, ausgewogen, mehrDaten
    var id: String { rawValue }
    var title: String {
        switch self {
        case .stabil:     return "Stabil"
        case .ausgewogen: return "Ausgewogen"
        case .mehrDaten:  return "Mehr Daten"
        }
    }
    var subtitle: String {
        switch self {
        case .stabil:     return "Maximale Verbindungssicherheit, etwas weniger Empfang"
        case .ausgewogen: return "Empfohlen – gute Balance aus Empfang & Stabilität"
        case .mehrDaten:  return "Mehr V2X-Empfang, knappere Stabilitätsreserve"
        }
    }
    /// (discSniff, discBle, connSniff, connBle) in ms — all BLE-safe.
    var cycle: (discSniff: Int, discBle: Int, connSniff: Int, connBle: Int) {
        switch self {
        case .stabil:     return (1000, 1000, 300, 400)
        case .ausgewogen: return (1000, 1000, 500, 400)
        case .mehrDaten:  return (1000, 1000, 600, 400)
        }
    }
    /// Best-matching preset for a device-reported config (keyed on connSniff).
    static func matching(connSniff: Int) -> CycleProfile {
        switch connSniff {
        case ..<400:    return .stabil
        case 400..<600: return .ausgewogen
        default:        return .mehrDaten
        }
    }
}

/// User-facing settings, persisted in UserDefaults.
final class AppSettings: ObservableObject {
    /// Map base layer — Apple maps plus OSM raster sources (cached offline).
    enum MapLayer: String, CaseIterable, Identifiable {
        case appleStandard, appleHybrid, osmStandard, osmDark, osmSatellite, osmTransport, osmHumanitarian
        var id: String { rawValue }
        var title: String {
            switch self {
            case .appleStandard:  return "Apple Karte"
            case .appleHybrid:    return "Apple Hybrid"
            case .osmStandard:    return "OSM Standard"
            case .osmDark:        return "OSM Dark"
            case .osmSatellite:   return "Satellit"
            case .osmTransport:   return "ÖPNV"
            case .osmHumanitarian: return "Humanitarian"
            }
        }
        /// Raster tile template, or nil for Apple's native base map.
        var urlTemplate: String? {
            switch self {
            case .appleStandard, .appleHybrid: return nil
            case .osmStandard:    return "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
            case .osmDark:        return "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
            case .osmSatellite:   return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
            case .osmTransport:   return "https://tile.memomaps.de/tilegen/{z}/{x}/{y}.png"
            case .osmHumanitarian: return "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png"
            }
        }
    }

    @Published var mapLayer: MapLayer { didSet { d.set(mapLayer.rawValue, forKey: "mapLayer") } }
    @Published var follow: Bool       { didSet { d.set(follow, forKey: "follow") } }
    @Published var bearingUp: Bool    { didSet { d.set(bearingUp, forKey: "bearingUp") } }
    @Published var geiger: Bool       { didSet { d.set(geiger, forKey: "geiger") } }
    @Published var notifyHazards: Bool { didSet { d.set(notifyHazards, forKey: "notifyHazards") } }
    @Published var trackRecording: Bool { didSet { d.set(trackRecording, forKey: "track") } }
    /// Maximum-verbosity debug logging on demand: BLE raw chunks, per-frame decode
    /// dumps, MQTT/station events — all into the Diagnose-Log. Off by default.
    @Published var debugVerbose: Bool {
        didSet { d.set(debugVerbose, forKey: "debugVerbose"); Diag.shared.verbose = debugVerbose }
    }

    /// Which message types are shown on the map and in the log.
    @Published var visibleTypes: Set<MsgType> {
        didSet { d.set(visibleTypes.map { $0.rawValue }, forKey: "visibleTypes") }
    }

    @Published var discSniffMs: Int { didSet { d.set(discSniffMs, forKey: "discSniff") } }
    @Published var discBleMs: Int   { didSet { d.set(discBleMs, forKey: "discBle") } }
    @Published var connSniffMs: Int { didSet { d.set(connSniffMs, forKey: "connSniff") } }
    @Published var connBleMs: Int   { didSet { d.set(connBleMs, forKey: "connBle") } }

    /// User-facing receiver mode. Bundles BLE-safe cycle values; changing it
    /// updates the four cycle fields below (which are then sent to the device).
    @Published var cycleProfile: CycleProfile {
        didSet {
            d.set(cycleProfile.rawValue, forKey: "cycleProfile")
            applyProfile()
        }
    }

    @Published var mqttEnabled: Bool { didSet { d.set(mqttEnabled, forKey: "mqttEnabled") } }
    @Published var mqttHost: String  { didSet { d.set(mqttHost, forKey: "mqttHost") } }
    @Published var mqttPort: Int     { didSet { d.set(mqttPort, forKey: "mqttPort") } }
    @Published var mqttTLS: Bool     { didSet { d.set(mqttTLS, forKey: "mqttTLS") } }
    @Published var nodeId: String    { didSet { d.set(nodeId, forKey: "nodeId") } }

    let mqttClientId = "v2x2mapIOS"

    private let d = UserDefaults.standard

    init() {
        mapLayer = MapLayer(rawValue: d.string(forKey: "mapLayer") ?? "osmStandard") ?? .osmStandard
        follow = (d.object(forKey: "follow") as? Bool) ?? true
        bearingUp = d.bool(forKey: "bearingUp")
        geiger = d.bool(forKey: "geiger")
        notifyHazards = (d.object(forKey: "notifyHazards") as? Bool) ?? true
        trackRecording = d.bool(forKey: "track")
        debugVerbose = d.bool(forKey: "debugVerbose")
        if let arr = d.stringArray(forKey: "visibleTypes") {
            visibleTypes = Set(arr.compactMap { MsgType(rawValue: $0) })
        } else {
            visibleTypes = Set(MsgType.allCases)
        }
        let prof = CycleProfile(rawValue: d.string(forKey: "cycleProfile") ?? "") ?? .ausgewogen
        cycleProfile = prof
        let cyc = prof.cycle
        discSniffMs = cyc.discSniff; discBleMs = cyc.discBle
        connSniffMs = cyc.connSniff; connBleMs = cyc.connBle
        mqttEnabled = d.bool(forKey: "mqttEnabled")
        mqttHost    = d.string(forKey: "mqttHost") ?? "cits1.opentrafficmap.org"
        mqttPort    = (d.object(forKey: "mqttPort") as? Int) ?? 8883
        mqttTLS     = (d.object(forKey: "mqttTLS") as? Bool) ?? true
        nodeId      = d.string(forKey: "nodeId") ?? "v2x2mapIOS"
        Diag.shared.verbose = debugVerbose
    }

    func toggleType(_ t: MsgType) {
        if visibleTypes.contains(t) { visibleTypes.remove(t) } else { visibleTypes.insert(t) }
    }

    /// Copy the selected preset's BLE-safe values into the cycle fields.
    private func applyProfile() {
        let c = cycleProfile.cycle
        discSniffMs = c.discSniff; discBleMs = c.discBle
        connSniffMs = c.connSniff; connBleMs = c.connBle
    }
}
