// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MessagesView",
    platforms: [
        .iOS(.v14),
    ],
    products: [
        .library(name: "MessagesView", targets: ["MessagesView"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.1"),
    ],
    targets: [
        .target(
            name: "MessagesView",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ]
        ),
    ]
)
