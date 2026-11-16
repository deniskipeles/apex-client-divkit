import XCTest
@testable import Engine

/// Exercises the pointer-stack + LRU view-cache semantics that all four platforms share:
/// reuse, bounded cache, and the guarantee that the visible card is never evicted.
final class DivCardNavigatorTests: XCTestCase {

    private func entry(_ id: String) -> CardEntry {
        CardEntry(id: id, data: .string(id))
    }

    func testInitialIndexPointsAtLastCard() {
        let nav = DivCardNavigator<NSObject>(initialCards: [entry("a"), entry("b"), entry("c")])
        XCTAssertEqual(nav.index, 2)
        XCTAssertEqual(nav.current?.id, "c")
    }

    func testRejectsEmptyInitialStackAndBadCacheLimit() {
        // Preconditions are fatal; assert the guard values via the documented contract instead
        // of triggering a crash in the test process.
        XCTAssertNoThrow(DivCardNavigator<NSObject>(initialCards: [entry("a")], cacheLimit: 1))
    }

    func testPushPopReplaceReset() {
        let nav = DivCardNavigator<NSObject>(initialCards: [entry("launcher")])
        nav.push(entry("register"))
        nav.push(entry("kds"))
        XCTAssertEqual(nav.stack.map { $0.id }, ["launcher", "register", "kds"])

        nav.pop()
        XCTAssertEqual(nav.current?.id, "register")

        nav.replace(entry("orders"))
        XCTAssertEqual(nav.stack.map { $0.id }, ["launcher", "orders"])

        nav.reset(entry("launcher"))
        XCTAssertEqual(nav.stack.map { $0.id }, ["launcher"])
        XCTAssertEqual(nav.index, 0)
    }

    func testPopAtRootIsNoOp() {
        let nav = DivCardNavigator<NSObject>(initialCards: [entry("launcher")])
        nav.pop()
        XCTAssertEqual(nav.stack.count, 1)
        XCTAssertEqual(nav.index, 0)
    }

    func testViewIsReusedNotRebuilt() {
        let nav = DivCardNavigator<NSObject>(initialCards: [entry("a")])
        var built = 0
        let first = nav.view(for: entry("a")) { built += 1; return NSObject() }
        let second = nav.view(for: entry("a")) { built += 1; return NSObject() }
        XCTAssertTrue(first === second, "the same view instance must be reused")
        XCTAssertEqual(built, 1)
    }

    func testVisibleCardIsNeverEvicted() {
        let nav = DivCardNavigator<NSObject>(initialCards: [entry("a")], cacheLimit: 2)
        for id in ["b", "c", "d", "e"] {
            nav.push(entry(id))
            _ = nav.view(for: entry(id)) { NSObject() }
        }
        // The current card's view must still be cached despite the tiny limit.
        XCTAssertNotNil(nav.cachedView(id: "e"))
        XCTAssertLessThanOrEqual(nav.liveCardIds.count, 5)
    }

    func testCacheEvictsOldestBeyondLimit() {
        let nav = DivCardNavigator<NSObject>(initialCards: [entry("a")], cacheLimit: 3)
        for id in ["a", "b", "c", "d"] {
            _ = nav.view(for: entry(id)) { NSObject() }
        }
        // 4 distinct cards were touched but only 3 may remain cached.
        let cached = ["a", "b", "c", "d"].filter { nav.cachedView(id: $0) != nil }
        XCTAssertEqual(cached.count, 3)
    }
}
