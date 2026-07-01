import Foundation

/// Records decoded frames to a standard `.pcap` file (link type 105 = IEEE 802.11),
/// openable in Wireshark. Port of the Android `FrameRecorder`.
final class PcapRecorder {
    private(set) var isRecording = false
    private(set) var fileURL: URL?
    private var handle: FileHandle?

    /// Starts a new recording in the app's Documents directory. Returns its URL.
    @discardableResult
    func start() -> URL? {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let name = "v2x_\(Int(Date().timeIntervalSince1970)).pcap"
        let url = dir.appendingPathComponent(name)
        guard FileManager.default.createFile(atPath: url.path, contents: globalHeader()),
              let h = try? FileHandle(forWritingTo: url) else { return nil }
        h.seekToEndOfFile()
        handle = h
        fileURL = url
        isRecording = true
        return url
    }

    func write(_ frame: Frame) {
        guard isRecording, let h = handle else { return }
        var rec = Data()
        rec.append(le32(frame.sec))
        rec.append(le32(frame.usec))
        rec.append(le32(UInt32(frame.payload.count)))   // incl_len
        rec.append(le32(UInt32(frame.payload.count)))   // orig_len
        rec.append(contentsOf: frame.payload)
        h.write(rec)
    }

    func stop() {
        try? handle?.close()
        handle = nil
        isRecording = false
    }

    private func globalHeader() -> Data {
        var d = Data()
        d.append(le32(0xa1b2_c3d4))   // magic
        d.append(le16(2)); d.append(le16(4))   // version 2.4
        d.append(le32(0))             // thiszone
        d.append(le32(0))             // sigfigs
        d.append(le32(65535))         // snaplen
        d.append(le32(105))           // LINKTYPE_IEEE802_11
        return d
    }

    private func le32(_ v: UInt32) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 4) }
    private func le16(_ v: UInt16) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 2) }
}
