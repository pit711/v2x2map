import Foundation

/// A WGS84 coordinate. Replaces the Kotlin `Pair<Double, Double>` lat/lon.
public struct GeoPoint: Equatable, Sendable {
    public let lat: Double
    public let lon: Double
    public init(_ lat: Double, _ lon: Double) {
        self.lat = lat
        self.lon = lon
    }
}

/// One ITS-G5 sniffer frame as it arrived from the ESP32-C5 over BLE,
/// with whatever the lightweight `ItsG5Decoder` could pull out of the payload.
/// Swift port of the Kotlin `Frame` data class.
public struct Frame: Identifiable, Sendable {
    public let seq: Int64
    public let sec: UInt32
    public let usec: UInt32
    public let payload: [UInt8]
    public let etherType: Int?
    public let msgType: MsgType
    public let stationId: UInt64?
    public let stationType: Int?       // ETSI StationType (5-bit: 0=unknown 5=passengerCar 15=RSU)
    public let latLon: GeoPoint?
    public let headingDeg: Double?
    public let speedMps: Double?
    public let spatPhase: SpatTemParser.Phase?
    public let secured: Bool?          // GN Basic Header NH==2 → IEEE 1609.2 signed packet
    public let denmCause: DenmParser.Cause?

    public var id: Int64 { seq }
    public var len: Int { payload.count }

    public init(
        seq: Int64,
        sec: UInt32,
        usec: UInt32,
        payload: [UInt8],
        etherType: Int?,
        msgType: MsgType,
        stationId: UInt64?,
        stationType: Int? = nil,
        latLon: GeoPoint?,
        headingDeg: Double?,
        speedMps: Double?,
        spatPhase: SpatTemParser.Phase? = nil,
        secured: Bool? = nil,
        denmCause: DenmParser.Cause? = nil
    ) {
        self.seq = seq
        self.sec = sec
        self.usec = usec
        self.payload = payload
        self.etherType = etherType
        self.msgType = msgType
        self.stationId = stationId
        self.stationType = stationType
        self.latLon = latLon
        self.headingDeg = headingDeg
        self.speedMps = speedMps
        self.spatPhase = spatPhase
        self.secured = secured
        self.denmCause = denmCause
    }

    public func hexPreview(maxBytes: Int = 32) -> String {
        let n = min(payload.count, maxBytes)
        return payload[0..<n].map { String(format: "%02x", $0) }.joined()
    }
}
