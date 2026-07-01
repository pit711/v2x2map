import Foundation

/// Parses a `.pcap` file (as written by `PcapRecorder`) back into timed records for replay.
enum PcapReader {
    static func read(_ url: URL) -> [ReplayPlayer.Rec] {
        guard let data = try? Data(contentsOf: url), data.count > 24 else { return [] }
        let b = [UInt8](data)
        func le32(_ o: Int) -> UInt32 {
            UInt32(b[o]) | (UInt32(b[o + 1]) << 8) | (UInt32(b[o + 2]) << 16) | (UInt32(b[o + 3]) << 24)
        }
        var recs: [ReplayPlayer.Rec] = []
        var i = 24   // skip the 24-byte global header
        while i + 16 <= b.count {
            let sec = le32(i)
            let usec = le32(i + 4)
            let inclLen = Int(le32(i + 8))
            i += 16
            guard inclLen >= 0, i + inclLen <= b.count else { break }
            recs.append(.init(sec: sec, usec: usec, payload: Array(b[i ..< i + inclLen])))
            i += inclLen
        }
        return recs
    }
}
