import Foundation

/// Minimal IEEE 802.11p / LLC-SNAP / GeoNetworking / BTP decoder.
/// Swift port of the Kotlin `ItsG5Decoder` object — behaviour-for-behaviour.
///
/// We don't run a full ASN.1 decoder; instead we lean on cheap signals:
///   • BTP destination port → message type (CAM / DENM / SPATEM / MAPEM / …)
///   • GeoNetworking Source Long Position Vector → lat/lon, heading, speed
///   • GeoNetworking Source Address → station ID + station type
public enum ItsG5Decoder {

    private static let etherTypeITSG5 = 0x8947
    private static let hdrLengths = [24, 26, 30, 32]

    public struct Decoded: Sendable {
        public var etherType: Int?
        public var msgType: MsgType
        public var stationId: UInt64?
        public var stationType: Int?
        public var latLon: GeoPoint?
        public var headingDeg: Double?
        public var speedMps: Double?
        public var btpDstPort: Int?
        public var spatPhase: SpatTemParser.Phase?
        public var denmCause: DenmParser.Cause?
        public var secured: Bool?

        public init(
            etherType: Int? = nil,
            msgType: MsgType = .unknown,
            stationId: UInt64? = nil,
            stationType: Int? = nil,
            latLon: GeoPoint? = nil,
            headingDeg: Double? = nil,
            speedMps: Double? = nil,
            btpDstPort: Int? = nil,
            spatPhase: SpatTemParser.Phase? = nil,
            denmCause: DenmParser.Cause? = nil,
            secured: Bool? = nil
        ) {
            self.etherType = etherType
            self.msgType = msgType
            self.stationId = stationId
            self.stationType = stationType
            self.latLon = latLon
            self.headingDeg = headingDeg
            self.speedMps = speedMps
            self.btpDstPort = btpDstPort
            self.spatPhase = spatPhase
            self.denmCause = denmCause
            self.secured = secured
        }
    }

    public static func decodeFull(_ p: [UInt8]) -> Decoded {
        let et = sniffEtherType(p)
        if et == nil || et != etherTypeITSG5 {
            return Decoded(etherType: et)
        }

        for hdrLen in hdrLengths {
            // Need at least 802.11 + LLC/SNAP + Basic + Common to read HT.
            if p.count < hdrLen + 8 + 4 + 8 { continue }

            // Verify LLC/SNAP magic — rejects wrong header-length guesses.
            if p[hdrLen + 0] != 0xAA || p[hdrLen + 1] != 0xAA || p[hdrLen + 2] != 0x03
                || p[hdrLen + 3] != 0x00 || p[hdrLen + 4] != 0x00 || p[hdrLen + 5] != 0x00 {
                continue
            }

            let basicOff = hdrLen + 8
            let commonOff = basicOff + 4

            // GN Basic Header NH (low nibble of byte 0). NH=2 → IEEE 1609.2 secured.
            let nh = Int(p[basicOff]) & 0x0F
            let secured = nh == 2

            let innerOff: Int
            if secured {
                let r = findInnerGnCommonHeader(p, commonOff, min(commonOff + 20, p.count - 36))
                if r < 0 { continue }
                innerOff = r
            } else {
                innerOff = commonOff
            }

            if p.count < innerOff + 8 { continue }

            let htHst = Int(p[innerOff + 1]) & 0xFF
            let ht = (htHst >> 4) & 0x0F
            let hst = htHst & 0x0F

            let extHdrLen: Int
            switch ht {
            case 1:  extHdrLen = 4    // BEACON
            case 2:  extHdrLen = 48   // GUC
            case 3:  extHdrLen = 56   // GAC
            case 4:  extHdrLen = 44   // GBC
            case 5:  extHdrLen = 28   // SHB / TSB
            case 6:  extHdrLen = 36   // LS
            default: extHdrLen = 28   // best-effort fallback
            }

            // SHB (ht=5, hst=0) puts the Source LPV directly at the start of the
            // extended header; every other type prepends 2-byte seq + 2-byte reserved.
            let srcPosInExt = (ht == 5 && hst == 0) ? 0 : 4
            let srcPosOff = innerOff + 8 + srcPosInExt
            let btpOff = innerOff + 8 + extHdrLen
            if p.count < btpOff + 4 { continue }

            // Source Long Position Vector (24 B): GnAddr 8 + TST 4 + LAT 4 + LON 4 + PAI|SPD 2 + HDG 2
            let gnAddrHi = beU32(p, srcPosOff + 0)
            let gnAddrLo = beU32(p, srcPosOff + 4)
            let stationId = (UInt64(gnAddrHi) << 32) | UInt64(gnAddrLo)
            // StationType from GN addr byte 0 bits 6..2 (ETSI EN 302 636-4-1 §9.1.3)
            let stationType = (Int(p[srcPosOff + 0]) >> 2) & 0x1F

            // Some RSUs use an 8-byte TAI timestamp, shifting lat/lon 4 bytes later.
            // If standard offsets produce an impossible coordinate, try shifted offsets.
            var latRaw = readBeI32(p, srcPosOff + 12)
            var lonRaw = readBeI32(p, srcPosOff + 16)
            var pshOff = srcPosOff + 20
            if !(-90.0 ... 90.0).contains(Double(latRaw) / 1e7) && p.count >= srcPosOff + 28 {
                let latShifted = readBeI32(p, srcPosOff + 16)
                let lonShifted = readBeI32(p, srcPosOff + 20)
                if (-90.0 ... 90.0).contains(Double(latShifted) / 1e7)
                    && (-180.0 ... 180.0).contains(Double(lonShifted) / 1e7) {
                    latRaw = latShifted
                    lonRaw = lonShifted
                    pshOff = srcPosOff + 24
                }
            }

            let psh = beU32(p, pshOff)
            let speedRaw = Int((psh >> 16) & 0x7FFF)
            let speedSigned = speedRaw >= 0x4000 ? speedRaw - 0x8000 : speedRaw
            let headingRaw = Int(psh & 0xFFFF)
            let speed = Double(speedSigned) / 100.0
            let heading = Double(headingRaw) / 10.0

            let lat = Double(latRaw) / 1e7
            let lon = Double(lonRaw) / 1e7
            let latLon: GeoPoint? =
                ((-90.0 ... 90.0).contains(lat) && (-180.0 ... 180.0).contains(lon)
                    && (lat != 0.0 || lon != 0.0)) ? GeoPoint(lat, lon) : nil

            let dstPort = (Int(p[btpOff]) << 8) | Int(p[btpOff + 1])
            let msgType = MsgType.fromBtpPort(dstPort)
            let spatPhase = msgType == .spatem ? SpatTemParser.extractPhase(p, btpOff: btpOff) : nil
            let denmCause = msgType == .denm ? DenmParser.extractCause(p, btpOff: btpOff) : nil

            return Decoded(
                etherType: et,
                msgType: msgType,
                stationId: stationId,
                stationType: stationType,
                latLon: latLon,
                headingDeg: (latLon != nil && headingRaw != 0xFFFF) ? heading : nil,
                speedMps: latLon != nil ? speed : nil,
                btpDstPort: dstPort,
                spatPhase: spatPhase,
                denmCause: denmCause,
                secured: secured
            )
        }
        return Decoded(etherType: et)
    }

    public static func sniffEtherType(_ p: [UInt8]) -> Int? {
        for hdrLen in hdrLengths {
            if p.count < hdrLen + 8 { continue }
            if p[hdrLen + 0] == 0xAA && p[hdrLen + 1] == 0xAA && p[hdrLen + 2] == 0x03
                && p[hdrLen + 3] == 0x00 && p[hdrLen + 4] == 0x00 && p[hdrLen + 5] == 0x00 {
                return (Int(p[hdrLen + 6]) << 8) | Int(p[hdrLen + 7])
            }
        }
        return nil
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static func beU32(_ p: [UInt8], _ o: Int) -> UInt32 {
        (UInt32(p[o]) << 24) | (UInt32(p[o + 1]) << 16) | (UInt32(p[o + 2]) << 8) | UInt32(p[o + 3])
    }

    private static func readBeI32(_ p: [UInt8], _ o: Int) -> Int {
        Int(Int32(bitPattern: beU32(p, o)))
    }

    /// Locates the inner GN Common Header inside an IEEE 1609.2 security envelope.
    private static func findInnerGnCommonHeader(_ p: [UInt8], _ start: Int, _ end: Int) -> Int {
        var off = start
        while off < end {
            if off + 8 > p.count { break }
            let nhInner = Int(p[off]) >> 4
            let htInner = Int(p[off + 1]) >> 4
            let plenInner = (Int(p[off + 4]) << 8) | Int(p[off + 5])
            if (0 ... 2).contains(nhInner) && (4 ... 6).contains(htInner) && (1 ... 999).contains(plenInner) {
                return off
            }
            off += 1
        }
        return -1
    }
}
