// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "MaestroDriverLib",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(
            name: "MaestroDriverLib",
            targets: ["MaestroDriverLib"]
        ),
    ],
    targets: [
        .target(
            name: "MaestroDriverLib",
            path: "Sources/MaestroDriverLib"
        ),
        .testTarget(
            name: "MaestroDriverLibTests",
            dependencies: ["MaestroDriverLib"],
            path: "Tests/MaestroDriverLibTests"
        ),
    ]
)