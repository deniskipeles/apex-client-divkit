// swift-tools-version:5.9
import PackageDescription

// Contract-only package. It compiles Sources/Contract so the cross-platform contract can be
// verified independently of the Xcode app target, which additionally depends on DivKit /
// DGCharts / SwiftUI.
//
// On Apple platforms the crypto mirror uses CryptoKit. On Linux (CI, `swift test`) CryptoKit
// is unavailable, so the same Curve25519 API is provided by swift-crypto; the sources select
// it with `#if canImport(CryptoKit)`.
let package = Package(
    name: "ApexContract",
    platforms: [.iOS(.v16), .macOS(.v12)],
    products: [
        .library(name: "Contract", targets: ["Contract"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0")
    ],
    targets: [
        .target(
            name: "Contract",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))
            ],
            path: "Sources/Contract"
        ),
        .testTarget(
            name: "ContractTests",
            dependencies: [
                "Contract",
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))
            ],
            path: "Tests/ContractTests"
        )
    ]
)
