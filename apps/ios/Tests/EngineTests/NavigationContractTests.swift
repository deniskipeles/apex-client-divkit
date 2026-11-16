import XCTest
@testable import Engine

/// Pins the iOS navigation parser against the shared fixtures in
/// packages/contract/fixtures/navigation/v1. Any drift between this parser and the other
/// three platforms turns CI red.
final class NavigationContractTests: XCTestCase {

    private var fixtureDir: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // EngineTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // apps
            .deletingLastPathComponent() // repo root
            .appendingPathComponent("packages/contract/fixtures/navigation/v1")
    }

    func testParseFixturesMatchExpected() throws {
        let files = try FileManager.default
            .contentsOfDirectory(at: fixtureDir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }

        XCTAssertEqual(files.count, 6, "expected 6 navigation fixtures")

        for file in files {
            let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
            let input = try XCTUnwrap(raw?["input"] as? String)
            let expected = try XCTUnwrap(raw?["expected"] as? [String: String])

            let parsed = NavigationUrl.parse(input)
            XCTAssertNotNil(parsed, "\(file.lastPathComponent): expected a parse, got nil")
            XCTAssertEqual(parsed?.fixtureObject, expected, file.lastPathComponent)
        }
    }

    func testParameterlessOpsCarryNoCardId() {
        XCTAssertEqual(NavigationUrl.parse("nav://pop"), .pop)
        XCTAssertEqual(NavigationUrl.parse("nav://pop")?.fixtureObject, ["op": "pop"])
        XCTAssertEqual(NavigationUrl.parse("sheet://dismiss")?.fixtureObject, ["op": "sheet-dismiss"])
    }

    func testUnresolvableAndForeignUrlsReturnNil() {
        XCTAssertNil(NavigationUrl.parse("nav://push"))
        XCTAssertNil(NavigationUrl.parse("nav://push?id="))
        XCTAssertNil(NavigationUrl.parse("sheet://present"))
        XCTAssertNil(NavigationUrl.parse("tools://device.haptics"))
        XCTAssertNil(NavigationUrl.parse("https://example.com"))
        XCTAssertNil(NavigationUrl.parse("not a url"))
    }

    func testRouterPushResolvesThroughRegistry() {
        let registry = CardRegistry()
        registry.register(id: "register", data: .string("card"))
        let nav = DivCardNavigator<NSObject>(initialCards: [CardEntry(id: "launcher", data: .string("root"))])
        let router = NavigationRouter(navigator: nav, registry: registry)

        XCTAssertTrue(router.handle("nav://push?id=register"))
        XCTAssertEqual(nav.current?.id, "register")
        XCTAssertEqual(nav.stack.count, 2)
    }

    func testRouterPushUnknownCardIsSilentNoOp() {
        let nav = DivCardNavigator<NSObject>(initialCards: [CardEntry(id: "launcher", data: .string("root"))])
        let router = NavigationRouter(navigator: nav, registry: CardRegistry())

        XCTAssertTrue(router.handle("nav://push?id=missing"))
        XCTAssertEqual(nav.current?.id, "launcher")
        XCTAssertEqual(nav.stack.count, 1)
    }

    func testRouterIgnoresForeignUrls() {
        let nav = DivCardNavigator<NSObject>(initialCards: [CardEntry(id: "launcher", data: .string("root"))])
        let router = NavigationRouter(navigator: nav, registry: CardRegistry())
        XCTAssertFalse(router.handle("tools://device.haptics"))
    }
}
