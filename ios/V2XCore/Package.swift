// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "V2XCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),   // so the pure-logic core can be unit-tested with `swift test` on the Mac
    ],
    products: [
        .library(name: "V2XCore", targets: ["V2XCore"]),
    ],
    targets: [
        .target(name: "V2XCore"),
        .testTarget(name: "V2XCoreTests", dependencies: ["V2XCore"]),
    ]
)
