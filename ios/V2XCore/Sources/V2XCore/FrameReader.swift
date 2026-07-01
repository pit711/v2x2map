import Foundation

/// Resync parser for the ESP-side wire format:
///
///     magic[4]  = "ITS5"
///     sec       u32 LE
///     usec      u32 LE
///     len       u16 LE
///     payload   <len> bytes
///
/// Drops everything that's not a magic-prefixed frame (ROM bootloader text,
/// stray log bytes, etc.). Swift port of the Kotlin `FrameReader` — same logic
/// as `bridge/its_g5_bridge.py:FrameReader`.
public final class FrameReader {
    public static let headerLen = 14
    public static let maxPayload = 4096

    private var buf: [UInt8] = []
    private var seq: Int64 = 0

    public init() {}

    /// Feed raw bytes from the BLE notify characteristic (or serial port).
    /// Returns any complete frames found.
    public func feed(_ chunk: [UInt8]) -> [Frame] {
        buf.append(contentsOf: chunk)
        var out: [Frame] = []

        while true {
            let mIdx = findMagic()
            if mIdx < 0 {
                // Keep up to 3 trailing bytes (magic could span the next read).
                if buf.count > 3 { buf.removeFirst(buf.count - 3) }
                return out
            }
            // Drop pre-magic noise.
            if mIdx > 0 { buf.removeFirst(mIdx) }
            if buf.count < Self.headerLen { return out }

            let sec = leU32(buf, 4)
            let usec = leU32(buf, 8)
            let plen = Int(leU16(buf, 12))

            if plen > Self.maxPayload {
                // implausible: drop this magic and resync
                buf.removeFirst(4)
                continue
            }
            if buf.count < Self.headerLen + plen { return out }

            // Consume header + payload.
            buf.removeFirst(Self.headerLen)
            let payload = Array(buf[0 ..< plen])
            buf.removeFirst(plen)

            let d = ItsG5Decoder.decodeFull(payload)
            seq += 1
            out.append(Frame(
                seq: seq,
                sec: sec,
                usec: usec,
                payload: payload,
                etherType: d.etherType,
                msgType: d.msgType,
                stationId: d.stationId,
                stationType: d.stationType,
                latLon: d.latLon,
                headingDeg: d.headingDeg,
                speedMps: d.speedMps,
                spatPhase: d.spatPhase,
                secured: d.secured,
                denmCause: d.denmCause
            ))
        }
    }

    /// Index of the first "ITS5" magic in `buf`, or -1.
    private func findMagic() -> Int {
        if buf.count < 4 { return -1 }
        var i = 0
        let last = buf.count - 4
        while i <= last {
            if buf[i] == 0x49 && buf[i + 1] == 0x54 && buf[i + 2] == 0x53 && buf[i + 3] == 0x35 {
                return i
            }
            i += 1
        }
        return -1
    }

    private func leU32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | (UInt32(b[o + 1]) << 8) | (UInt32(b[o + 2]) << 16) | (UInt32(b[o + 3]) << 24)
    }

    private func leU16(_ b: [UInt8], _ o: Int) -> UInt16 {
        UInt16(b[o]) | (UInt16(b[o + 1]) << 8)
    }
}
