import Foundation

/// Vector maths shared by the embedding tools. Mirrors `engine.tools.vector.VectorMath`.
public enum VectorMath {

    public static func cosine(_ a: [Float], _ b: [Float]) -> Float {
        precondition(a.count == b.count, "dim mismatch \(a.count) vs \(b.count)")
        var dot: Float = 0, na: Float = 0, nb: Float = 0
        for i in a.indices {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if na == 0 || nb == 0 { return 0 }
        return min(max(dot / (na.squareRoot() * nb.squareRoot()), -1), 1)
    }

    public static func l2(_ v: [Float]) -> [Float] {
        var n: Float = 0
        for x in v { n += x * x }
        if n == 0 { return v }
        let inv = 1 / n.squareRoot()
        return v.map { $0 * inv }
    }
}

/// Little-endian float packing used for `vector_b64`. Mirrors the ByteBuffer helpers in
/// `NativeToolsSuite` / `IosVectorizeTool`.
public enum FloatPacking {

    public static func pack(_ values: [Float]) -> String {
        var data = Data(capacity: values.count * 4)
        for f in values {
            var le = f.bitPattern.littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        return data.base64EncodedString()
    }

    public static func unpack(_ base64: String) -> [Float] {
        guard let data = Data(base64Encoded: base64) else { return [] }
        var out: [Float] = []
        out.reserveCapacity(data.count / 4)
        var index = data.startIndex
        while index < data.endIndex {
            let end = data.index(index, offsetBy: 4, limitedBy: data.endIndex) ?? data.endIndex
            let chunk = data[index..<end]
            if chunk.count < 4 { break }
            let bits = chunk.withUnsafeBytes { $0.load(as: UInt32.self) }
            out.append(Float(bitPattern: UInt32(littleEndian: bits)))
            index = end
        }
        return out
    }
}
