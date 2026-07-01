import XCTest
@testable import V2XCore

final class DecoderTests: XCTestCase {

    // MARK: helpers

    /// Writes a big-endian Int32 into `buf` at `off`.
    private func putBE32(_ buf: inout [UInt8], _ off: Int, _ value: Int32) {
        let u = UInt32(bitPattern: value)
        buf[off + 0] = UInt8((u >> 24) & 0xFF)
        buf[off + 1] = UInt8((u >> 16) & 0xFF)
        buf[off + 2] = UInt8((u >> 8) & 0xFF)
        buf[off + 3] = UInt8(u & 0xFF)
    }

    /// Builds a synthetic unsecured SHB/CAM 802.11p payload (24-byte MAC header)
    /// at lat 48.1371°, lon 11.5761°, heading 90.0°, speed 13.89 m/s, passengerCar.
    private func makeCamPayload() -> [UInt8] {
        var p = [UInt8](repeating: 0, count: 80)

        // [24] 802.11 MAC header — left zero
        // [24..31] LLC/SNAP: AA AA 03 00 00 00  EtherType 0x8947
        p[24] = 0xAA; p[25] = 0xAA; p[26] = 0x03; p[27] = 0x00; p[28] = 0x00; p[29] = 0x00
        p[30] = 0x89; p[31] = 0x47
        // [32..35] GN Basic Header — NH=1 (unsecured)
        p[32] = 0x01
        // [36..43] GN Common Header — byte1 = HT/HST = 0x50 (HT=5 SHB, HST=0)
        p[37] = 0x50
        // [44..51] GN Address: byte0 bits6..2 = stationType 5 (passengerCar) → 0x14, then 6-byte MAC
        p[44] = 0x14; p[45] = 0x00
        p[46] = 0xAA; p[47] = 0xBB; p[48] = 0xCC; p[49] = 0xDD; p[50] = 0xEE; p[51] = 0xFF
        // [52..55] timestamp — zero
        // [56..59] latitude  (1/1e7 deg) = 48.1371 → 481_371_000
        putBE32(&p, 56, 481_371_000)
        // [60..63] longitude (1/1e7 deg) = 11.5761 → 115_761_000
        putBE32(&p, 60, 115_761_000)
        // [64..67] PAI|speed(15) hi, heading(16) lo → speed 1389 (×0.01) , heading 900 (×0.1)
        p[64] = 0x05; p[65] = 0x6D  // 0x056D = 1389
        p[66] = 0x03; p[67] = 0x84  // 0x0384 = 900
        // [68..71] reserved (rest of 28-byte SHB ext header)
        // [72..75] BTP-B: dstPort 2001 (CAM) = 0x07D1
        p[72] = 0x07; p[73] = 0xD1
        return p
    }

    /// Wraps a payload in the ESP wire frame: "ITS5" + sec + usec + len + payload.
    private func wireFrame(_ payload: [UInt8], sec: UInt32 = 0x1122_3344, usec: UInt32 = 0x5566_7788) -> [UInt8] {
        var f: [UInt8] = [0x49, 0x54, 0x53, 0x35] // "ITS5"
        func le32(_ v: UInt32) { f += [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)] }
        func le16(_ v: UInt16) { f += [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
        le32(sec); le32(usec); le16(UInt16(payload.count))
        f += payload
        return f
    }

    // MARK: decoder

    func testDecodeCam() {
        let d = ItsG5Decoder.decodeFull(makeCamPayload())
        XCTAssertEqual(d.etherType, 0x8947)
        XCTAssertEqual(d.msgType, .cam)
        XCTAssertEqual(d.secured, false)
        XCTAssertEqual(d.stationType, 5)
        XCTAssertEqual(d.stationId, 0x1400_AABB_CCDD_EEFF)
        XCTAssertNotNil(d.latLon)
        XCTAssertEqual(d.latLon!.lat, 48.1371, accuracy: 1e-6)
        XCTAssertEqual(d.latLon!.lon, 11.5761, accuracy: 1e-6)
        XCTAssertEqual(d.headingDeg!, 90.0, accuracy: 1e-9)
        XCTAssertEqual(d.speedMps!, 13.89, accuracy: 1e-9)
        XCTAssertEqual(d.btpDstPort, 2001)
    }

    func testNonItsG5IsIgnored() {
        var p = [UInt8](repeating: 0, count: 40)
        // LLC/SNAP at 24 with a non-ITS EtherType (IPv4 0x0800)
        p[24] = 0xAA; p[25] = 0xAA; p[26] = 0x03; p[27] = 0x00; p[28] = 0x00; p[29] = 0x00
        p[30] = 0x08; p[31] = 0x00
        let d = ItsG5Decoder.decodeFull(p)
        XCTAssertEqual(d.etherType, 0x0800)
        XCTAssertEqual(d.msgType, .unknown)
        XCTAssertNil(d.latLon)
    }

    // MARK: frame reader

    func testFrameReaderReassembles() {
        let reader = FrameReader()
        let wire = wireFrame(makeCamPayload())

        // Prepend junk, then split the wire frame across two BLE notifications.
        let junk: [UInt8] = [0x00, 0xDE, 0xAD, 0xBE]
        let stream = junk + wire
        let cut = stream.count / 2

        var frames = reader.feed(Array(stream[0 ..< cut]))
        XCTAssertTrue(frames.isEmpty, "frame should not be complete after first half")
        frames += reader.feed(Array(stream[cut ..< stream.count]))

        XCTAssertEqual(frames.count, 1)
        let f = frames[0]
        XCTAssertEqual(f.seq, 1)
        XCTAssertEqual(f.sec, 0x1122_3344)
        XCTAssertEqual(f.usec, 0x5566_7788)
        XCTAssertEqual(f.msgType, .cam)
        XCTAssertEqual(f.latLon, GeoPoint(48.1371, 11.5761))
        XCTAssertEqual(f.len, 80)
    }

    func testFrameReaderSkipsImplausibleLength() {
        let reader = FrameReader()
        // Magic + header claiming a 60000-byte payload → must resync, drop, no frame.
        var bad: [UInt8] = [0x49, 0x54, 0x53, 0x35]
        bad += [0, 0, 0, 0, 0, 0, 0, 0] // sec+usec
        bad += [0x60, 0xEA]             // len = 60000 (> MAX_PAYLOAD)
        let frames = reader.feed(bad)
        XCTAssertTrue(frames.isEmpty)
    }

    func testTwoFramesInOneChunk() {
        let reader = FrameReader()
        let two = wireFrame(makeCamPayload()) + wireFrame(makeCamPayload())
        let frames = reader.feed(two)
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].seq, 1)
        XCTAssertEqual(frames[1].seq, 2)
    }

    // MARK: msg-type mapping

    func testBtpPortMapping() {
        XCTAssertEqual(MsgType.fromBtpPort(2001), .cam)
        XCTAssertEqual(MsgType.fromBtpPort(2002), .denm)
        XCTAssertEqual(MsgType.fromBtpPort(2004), .spatem)
        XCTAssertEqual(MsgType.fromBtpPort(2012), .rtcmem)
        XCTAssertEqual(MsgType.fromBtpPort(9999), .unknown)
    }

    // MARK: bit reader

    func testBitReaderMSBFirst() {
        // 0b1010_1100 = 0xAC → first 4 bits MSB-first = 0b1010 = 10
        let b = BitReader([0xAC], startByte: 0)
        XCTAssertEqual(b.bits(4), 0b1010)
        XCTAssertEqual(b.bits(4), 0b1100)
        // reading past the end yields zeros
        XCTAssertEqual(b.bits(8), 0)
    }
}
