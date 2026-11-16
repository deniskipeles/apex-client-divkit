import XCTest
@testable import Engine

/// Tests for the pure workspace filesystem layer. Mirrors `WorkspaceFileSystemTest.kt`.
final class WorkspaceFileSystemTests: XCTestCase {

    private var root: String!

    override func setUpWithError() throws {
        root = NSTemporaryDirectory() + "/wsfs-\(UUID().uuidString)"
        try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(atPath: root)
    }

    func testRejectsNamesThatEscapeOrContainSeparators() {
        for bad in ["", "   ", ".", "..", "a/b", "a\\b", "../evil", "sub/../evil"] {
            XCTAssertFalse(WorkspaceFileSystem.isSafeName(bad), "expected unsafe: '\(bad)'")
        }
        for good in ["notes.txt", "media", "my folder", "a-b_c.1"] {
            XCTAssertTrue(WorkspaceFileSystem.isSafeName(good), "expected safe: '\(good)'")
        }
    }

    func testChildOfBlocksTraversalOutsideTheParent() {
        XCTAssertNotNil(WorkspaceFileSystem.childOf(root, "child"))
        XCTAssertNil(WorkspaceFileSystem.childOf(root, "../escape"))
        XCTAssertNil(WorkspaceFileSystem.childOf(root, "nested/../../escape"))
    }

    func testCreateRenameAndDeleteRoundTrip() throws {
        let dir = WorkspaceFileSystem.createDirectory(root, "media")
        XCTAssertNotNil(dir)
        XCTAssertTrue(WorkspaceFileSystem.toEntry(dir!).isDirectory)
        XCTAssertNil(WorkspaceFileSystem.createDirectory(root, "media"))

        let file = WorkspaceFileSystem.createTextFile(root, "notes", "hello")
        XCTAssertNotNil(file)
        XCTAssertEqual((file! as NSString).lastPathComponent, "notes.txt")
        XCTAssertEqual(try String(contentsOfFile: file!, encoding: .utf8), "hello")

        let renamed = WorkspaceFileSystem.rename(file!, "renamed.txt")
        XCTAssertNotNil(renamed)
        XCTAssertEqual((renamed! as NSString).lastPathComponent, "renamed.txt")
        XCTAssertFalse(FileManager.default.fileExists(atPath: file!))

        XCTAssertTrue(WorkspaceFileSystem.delete(renamed!))
        XCTAssertFalse(FileManager.default.fileExists(atPath: renamed!))
    }

    func testRenameRefusesToOverwriteAnExistingTarget() throws {
        let a = WorkspaceFileSystem.createTextFile(root, "a.txt", "a")!
        _ = WorkspaceFileSystem.createTextFile(root, "b.txt", "b")!
        XCTAssertNil(WorkspaceFileSystem.rename(a, "b.txt"))
        XCTAssertEqual(try String(contentsOfFile: a, encoding: .utf8), "a")
    }

    func testListingPutsDirectoriesFirstThenSortsCaseInsensitively() {
        _ = WorkspaceFileSystem.createDirectory(root, "zeta")
        _ = WorkspaceFileSystem.createDirectory(root, "alpha")
        _ = WorkspaceFileSystem.createTextFile(root, "Beta.txt", "b")
        _ = WorkspaceFileSystem.createTextFile(root, "apple.txt", "a")

        let names = WorkspaceFileSystem.listEntries(root).map(\.name)
        XCTAssertEqual(names, ["alpha", "zeta", "apple.txt", "Beta.txt"])
    }

    func testDirectorySizeAndHumanFormattingAggregateNestedFiles() throws {
        let nested = (root as NSString).appendingPathComponent("nested")
        try FileManager.default.createDirectory(atPath: nested, withIntermediateDirectories: true)
        try "12345".write(toFile: (root as NSString).appendingPathComponent("top.txt"), atomically: true, encoding: .utf8)
        try "123".write(toFile: (nested as NSString).appendingPathComponent("deep.txt"), atomically: true, encoding: .utf8)

        XCTAssertEqual(WorkspaceFileSystem.directorySize(root), 8)
        XCTAssertEqual(WorkspaceFileSystem.humanSize(8), "8 B")
        XCTAssertEqual(WorkspaceFileSystem.humanSize(1536), "1.5 KB")
    }

    func testTextPreviewReadsContentAndFlagsBinaryFiles() throws {
        let text = (root as NSString).appendingPathComponent("readme.md")
        try "# Title".write(toFile: text, atomically: true, encoding: .utf8)
        XCTAssertEqual(WorkspaceFileSystem.readTextPreview(text), "# Title")

        let binary = (root as NSString).appendingPathComponent("blob.bin")
        try Data([1, 2, 0, 4]).write(to: URL(fileURLWithPath: binary))
        XCTAssertNil(WorkspaceFileSystem.readTextPreview(binary))
    }

    func testTextPreviewTruncatesAtTheByteCap() throws {
        let big = (root as NSString).appendingPathComponent("big.txt")
        try String(repeating: "x", count: 10_000).write(toFile: big, atomically: true, encoding: .utf8)
        let preview = WorkspaceFileSystem.readTextPreview(big, maxBytes: 512)
        XCTAssertNotNil(preview)
        XCTAssertEqual(preview!.count, 512)
    }

    func testEntryClassifiesPreviewableExtensions() throws {
        let json = (root as NSString).appendingPathComponent("data.json")
        let png = (root as NSString).appendingPathComponent("pic.png")
        try "{}".write(toFile: json, atomically: true, encoding: .utf8)
        try "x".write(toFile: png, atomically: true, encoding: .utf8)
        XCTAssertTrue(WorkspaceFileSystem.toEntry(json).isTextPreviewable)
        XCTAssertFalse(WorkspaceFileSystem.toEntry(png).isTextPreviewable)
        XCTAssertEqual(WorkspaceFileSystem.toEntry(json).extension, "json")
    }
}
