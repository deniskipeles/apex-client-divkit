import XCTest
@testable import Engine

/// Pure-Swift tests for the bundled native POS icon catalog. Mirrors
/// `NativeIconCatalogTest.kt`.
final class NativeIconCatalogTests: XCTestCase {

    func testShipsALargeCuratedSet() {
        XCTAssertGreaterThanOrEqual(NativeIconCatalog.size, 100)
        XCTAssertEqual(NativeIconCatalog.icons.count, NativeIconCatalog.size)
    }

    func testEveryKeyResolvesToExactlyOneIcon() {
        let keys = NativeIconCatalog.icons.flatMap { [$0.name] + $0.aliases }
        XCTAssertEqual(keys.count, Set(keys).count, "duplicate icon keys")
        for icon in NativeIconCatalog.icons {
            for key in [icon.name] + icon.aliases {
                let resolved = NativeIconCatalog.find(key)
                XCTAssertNotNil(resolved, "unresolved key: \(key)")
                XCTAssertEqual(resolved?.name, icon.name, "key \(key) resolved to the wrong icon")
                XCTAssertEqual(NativeIconCatalog.canonicalName(key), icon.name)
            }
        }
    }

    func testEveryIconDeclaresMaterialAndCategory() {
        for icon in NativeIconCatalog.icons {
            XCTAssertFalse(icon.name.isEmpty)
            XCTAssertFalse(icon.material.isEmpty)
            XCTAssertTrue(icon.aliases.allSatisfy { !$0.isEmpty })
        }
        XCTAssertEqual(Set(NativeIconCatalog.icons.map(\.category)), Set(IconCategory.allCases))
    }

    func testEveryCategoryIsPopulated() {
        for category in NativeIconCatalog.categories {
            XCTAssertFalse(NativeIconCatalog.byCategory(category).isEmpty, "category \(category.label) is empty")
        }
    }

    func testResolvesCanonicalNativeUriForm() {
        XCTAssertEqual(NativeIconCatalog.find("icons://native/cart")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("icons://native/bell")?.name, "bell")
        XCTAssertEqual(NativeIconCatalog.find("icons://native/printer")?.name, "printer")
    }

    func testAcceptsShorthandUriCanonicalAliasAndMaterialCasing() {
        XCTAssertEqual(NativeIconCatalog.find("icons://cart")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("cart")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("Cart")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("shopping_cart")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("ShoppingCart")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("notification")?.name, "bell")
    }

    func testIgnoresQueryStringsAndFragments() {
        XCTAssertEqual(NativeIconCatalog.find("icons://native/cart?variant=filled")?.name, "cart")
        XCTAssertEqual(NativeIconCatalog.find("icons://native/cart#top")?.name, "cart")
    }

    func testRejectsForeignSchemesAndBlankInput() {
        XCTAssertNil(NativeIconCatalog.find("https://example.com/cart.png"))
        XCTAssertNil(NativeIconCatalog.find("file:///tmp/cart.png"))
        XCTAssertNil(NativeIconCatalog.find(""))
        XCTAssertNil(NativeIconCatalog.find("   "))
        XCTAssertNil(NativeIconCatalog.normalize("icons://"))
        XCTAssertNil(NativeIconCatalog.normalize("icons://native"))
        XCTAssertNil(NativeIconCatalog.normalize("icons://native/"))
    }

    func testUnknownNameIsNotReportedAsKnown() {
        XCTAssertNil(NativeIconCatalog.find("definitely_not_an_icon"))
        XCTAssertFalse(NativeIconCatalog.isKnown("definitely_not_an_icon"))
        XCTAssertEqual(NativeIconCatalog.normalize("definitely_not_an_icon"), "definitely_not_an_icon")
    }

    func testMaterialLookupRoundTrips() {
        for icon in NativeIconCatalog.icons {
            XCTAssertEqual(NativeIconCatalog.findByMaterial(icon.material), icon)
            XCTAssertEqual(NativeIconCatalog.find(icon.material)?.name, icon.name)
            XCTAssertEqual(NativeIconCatalog.materialFor(icon.name), icon.material)
        }
    }

    func testEveryMaterialIdIsUnique() {
        let materials = NativeIconCatalog.icons.map(\.material)
        XCTAssertEqual(materials.count, Set(materials).count)
    }

    func testLegacyResolveIconNamesStillResolve() {
        let legacy = [
            "add", "plus", "cart_add", "scan", "barcode", "scanner", "qr_code", "qr",
            "print", "printer", "receipt", "nfc", "tap", "card", "delete", "trash",
            "clear", "sync", "cloud", "check", "checkout", "pay", "inventory", "box",
            "refresh", "search", "ocr", "text", "read_text", "camera", "photo", "image",
            "lock", "shield", "e2ee", "vibration", "haptics", "volume", "beeper", "audio",
            "fingerprint", "biometrics", "paste", "clipboard", "notification", "push", "bell"
        ]
        let unresolved = legacy.filter { NativeIconCatalog.find($0) == nil }
        XCTAssertTrue(unresolved.isEmpty, "legacy names no longer resolve: \(unresolved)")
    }

    func testUriForBuildsCanonicalUris() {
        XCTAssertEqual(NativeIconCatalog.uriFor("cart"), "icons://native/cart")
        XCTAssertTrue(NativeIconCatalog.isIconUri("icons://native/cart"))
        XCTAssertTrue(NativeIconCatalog.isIconUri("ICONS://NATIVE/CART"))
        XCTAssertFalse(NativeIconCatalog.isIconUri("https://example.com/cart.png"))
    }

    func testCoversTheCommonPosVocabulary() {
        let names = [
            "cart", "checkout", "payment", "card", "cash", "receipt", "discount",
            "product", "stock", "warehouse", "shipping", "scan", "qr", "nfc",
            "customer", "bell", "error", "warning", "check", "clock", "calendar",
            "sync", "wifi", "bluetooth", "printer", "camera", "bar_chart", "lock",
            "fingerprint", "add", "edit", "delete", "search", "filter", "refresh",
            "home", "menu", "settings", "location", "call", "email", "chat"
        ]
        for name in names {
            XCTAssertTrue(NativeIconCatalog.isKnown(name), "missing common POS icon: \(name)")
        }
    }
}
