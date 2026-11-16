// swift-tools-version:5.9
import PackageDescription

// Cross-platform contract + portable engine package.
//
// `Contract` compiles Sources/Contract so the cross-platform contract can be verified
// independently of the Xcode app target, which additionally depends on DivKit / DGCharts /
// SwiftUI.
//
// `Engine` mirrors the Android `engine/` package (database, icons, notifications, tools,
// filesystem, sync) as portable Swift so it can be compiled and unit-tested on Linux CI and
// linked into the iOS app target. `CSQLite` is a system library shim so the engine's SQLite
// layer binds the platform's libsqlite3 on both Apple platforms and Linux.
//
// On Apple platforms the crypto mirror uses CryptoKit. On Linux (CI, `swift test`) CryptoKit
// is unavailable, so the same Curve25519 API is provided by swift-crypto; the sources select
// it with `#if canImport(CryptoKit)`.
let package = Package(
    name: "ApexContract",
    platforms: [.iOS(.v16), .macOS(.v12)],
    products: [
        .library(name: "Contract", targets: ["Contract"]),
        .library(name: "Engine", targets: ["Engine"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0")
    ],
    targets: [
        .systemLibrary(name: "CSQLite", path: "CSQLite"),
        .target(
            name: "Contract",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))
            ],
            path: "Sources/Contract"
        ),
        .target(
            name: "Engine",
            dependencies: ["Contract", "CSQLite"],
            path: "Sources/Engine"
        ),
        .testTarget(
            name: "ContractTests",
            dependencies: [
                "Contract",
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))
            ],
            path: "Tests/ContractTests"
        ),
        .testTarget(
            name: "EngineTests",
            dependencies: ["Engine", "Contract"],
            path: "Tests/EngineTests"
        )
    ]
)
