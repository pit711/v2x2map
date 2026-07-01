import Foundation

/// MSB-first bit reader over a byte array, starting at a given byte offset.
/// Shared port of the (near-identical) private `Bits` classes in the Kotlin
/// `DenmParser` and `SpatTemParser`. Reading past the end yields zero bits
/// (and does not advance), mirroring the Kotlin guard.
final class BitReader {
    private let b: [UInt8]
    private var pos: Int

    init(_ b: [UInt8], startByte: Int) {
        self.b = b
        self.pos = startByte * 8
    }

    func bit() -> Int {
        if pos / 8 >= b.count { return 0 }
        let v = (Int(b[pos / 8]) >> (7 - pos % 8)) & 1
        pos += 1
        return v
    }

    /// Reads `n` bits MSB-first. For `n` larger than `Int.bitWidth` the high
    /// bits overflow harmlessly — matching the Kotlin code, where such calls
    /// are only used to *skip* fields and their return value is discarded.
    func bits(_ n: Int) -> Int {
        var acc = 0
        for _ in 0..<n { acc = (acc << 1) | bit() }
        return acc
    }

    /// UPER constrained whole number: value encoded in ceil(log2(range)) bits.
    func constrained(_ lo: Int, _ hi: Int) -> Int {
        let range = hi - lo + 1
        if range <= 1 { return lo }
        let nbits = Int.bitWidth - (range - 1).leadingZeroBitCount
        return lo + bits(nbits)
    }
}
