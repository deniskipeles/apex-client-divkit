import Foundation
import UIKit
import Vision
import NaturalLanguage
import shared

public final class IosVectorizeTool: NSObject, ToolHandler {
    public let capability = "vectorize"
    public let requiredPermissions: Set<String> = []

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? "text").lowercased()
        let t0 = Date()

        switch op {
        case "ocr", "image_text":
            guard let img = loadImage(args["uri"] ?? args["image"] ?? "") else {
                return ToolResultFailed(tool: capability, message: "Missing/invalid image")
            }
            guard let ocr = recognizeText(img) else {
                return ToolResultFailed(tool: capability, message: "OCR failed")
            }
            if op == "ocr" {
                return ToolResultSuccess(data: [
                    "text": ocr.text,
                    "confidence": ocr.confidence,
                    "block_count": ocr.blockCount,
                    "model": "Apple Vision VNRecognizeTextRequest",
                    "latency_ms": Int64(Date().timeIntervalSince(t0) * 1000)
                ])
            }
            guard let vec = textEmbedding(ocr.text) else {
                return ToolResultFailed(tool: capability, message: "Text embedding failed")
            }
            return ToolResultSuccess(data: [
                "source_type": "image_text",
                "vector_b64": packFloats(vec),
                "dim": vec.count,
                "model": "NLEmbedding.sentenceEmbedding",
                "ocr_text": ocr.text,
                "ocr_confidence": ocr.confidence,
                "latency_ms": Int64(Date().timeIntervalSince(t0) * 1000)
            ])

        case "text":
            guard let text = args["text"] else {
                return ToolResultFailed(tool: capability, message: "Missing text")
            }
            guard let vec = textEmbedding(text) else {
                return ToolResultFailed(tool: capability, message: "Embedding failed")
            }
            return ToolResultSuccess(data: [
                "source_type": "text",
                "vector_b64": packFloats(vec),
                "dim": vec.count,
                "model": "NLEmbedding.sentenceEmbedding",
                "latency_ms": Int64(Date().timeIntervalSince(t0) * 1000)
            ])

        case "image":
            guard let img = loadImage(args["uri"] ?? args["image"] ?? "") else {
                return ToolResultFailed(tool: capability, message: "Missing/invalid image")
            }
            guard let vec = imageEmbedding(img) else {
                return ToolResultFailed(tool: capability, message: "Feature print failed")
            }
            return ToolResultSuccess(data: [
                "source_type": "image",
                "vector_b64": packFloats(vec),
                "dim": vec.count,
                "model": "Vision VNGenerateImageFeaturePrint",
                "latency_ms": Int64(Date().timeIntervalSince(t0) * 1000)
            ])

        case "similarity":
            guard let a = unpackFloats(args["a"] ?? ""), let b = unpackFloats(args["b"] ?? ""),
                  a.count == b.count else {
                return ToolResultFailed(tool: capability, message: "Bad vectors")
            }
            var dot = 0.0, na = 0.0, nb = 0.0
            for i in 0..<a.count { dot += Double(a[i]*b[i]); na += Double(a[i]*a[i]); nb += Double(b[i]*b[i]) }
            let sim = (na == 0 || nb == 0) ? 0 : dot / (na.squareRoot() * nb.squareRoot())
            return ToolResultSuccess(data: ["score": sim, "method": "cosine"])

        default:
            return ToolResultFailed(tool: capability, message: "Unknown op \(op)")
        }
    }

    // --- OCR (0 MB, OS) ---
    private func recognizeText(_ image: UIImage) -> (text: String, confidence: Float, blockCount: Int)? {
        guard let cg = image.cgImage else { return nil }
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.usesLanguageCorrection = true
        let handler = VNImageRequestHandler(cgImage: cg)
        try? handler.perform([req])
        let obs = req.results ?? []
        let text = obs.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        let conf = obs.compactMap { $0.topCandidates(1).first?.confidence }.reduce(0,+) / Float(max(obs.count,1))
        return (text, conf, obs.count)
    }

    // --- Text embedding (0 MB, OS, 512-d) ---
    private func textEmbedding(_ text: String) -> [Float]? {
        guard let emb = NLEmbedding.sentenceEmbedding(for: .english) else { return nil }
        guard let v = emb.vector(for: text) else { return nil }
        return v.map { Float($0) }
    }

    // --- Visual feature print (0 MB, OS, 2048-d) ---
    private func imageEmbedding(_ image: UIImage) -> [Float]? {
        guard let cg = image.cgImage else { return nil }
        let req = VNGenerateImageFeaturePrintRequest()
        let handler = VNImageRequestHandler(cgImage: cg)
        try? handler.perform([req])
        guard let obs = req.results?.first as? VNFeaturePrintObservation else { return nil }
        var v = [Float](repeating: 0, count: obs.elementCount)
        obs.data.withUnsafeBytes { raw in
            let f = raw.bindMemory(to: Float.self)
            for i in 0..<obs.elementCount { v[i] = f[i] }
        }
        return v
    }

    private func loadImage(_ uri: String) -> UIImage? {
        if uri.hasPrefix("file://") { return UIImage(contentsOfFile: String(uri.dropFirst(7))) }
        if uri.hasPrefix("data:image") , let r = uri.range(of: "base64,") {
            if let d = Data(base64Encoded: String(uri[r.upperBound...])) { return UIImage(data: d) }
        }
        return UIImage(contentsOfFile: uri)
    }

    private func packFloats(_ v: [Float]) -> String {
        var d = Data(capacity: v.count * 4)
        for f in v { var le = f.bitPattern.littleEndian; withUnsafeBytes(of: &le) { d.append(contentsOf: $0) } }
        return d.base64EncodedString()
    }
    private func unpackFloats(_ s: String) -> [Float]? {
        guard let d = Data(base64Encoded: s) else { return nil }
        return stride(from: 0, to: d.count, by: 4).map { i in
            d.subdata(in: i..<i+4).withUnsafeBytes { $0.load(as: UInt32.self) }.littleEndian.bitPattern
        }.map { Float(bitPattern: $0) }
    }
}