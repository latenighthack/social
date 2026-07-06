import XCTest
import UIKit

final class ScreenshotTests: XCTestCase {
    @MainActor
    func testCaptureAllFixtures() throws {
        let bundle = Bundle(for: Self.self)
        let outDir = screenshotDir()
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

        let width: CGFloat = 360
        let pad: CGFloat = 16
        let fixtures = Fixtures.load(bundle: bundle)
        XCTAssertFalse(fixtures.isEmpty, "no fixtures found in the test bundle")

        // A tall host window with a fixed width, so multiline labels get a real width to
        // wrap against and Auto Layout resolves the content height naturally.
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 4000))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()

        for fixture in fixtures {
            root.view.subviews.forEach { $0.removeFromSuperview() }

            let container = UIView()
            container.backgroundColor = UIColor(red: 0.06, green: 0.055, blue: 0.09, alpha: 1)
            container.translatesAutoresizingMaskIntoConstraints = false

            let content = Fixtures.render(fixture)
            content.translatesAutoresizingMaskIntoConstraints = false
            container.addSubview(content)

            var constraints = [
                content.topAnchor.constraint(equalTo: container.topAnchor, constant: pad),
                content.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -pad),
            ]
            if fixture.mode == "message" && !fixture.incoming {
                constraints.append(content.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -pad))
                constraints.append(content.leadingAnchor.constraint(greaterThanOrEqualTo: container.leadingAnchor, constant: pad))
            } else {
                constraints.append(content.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: pad))
                constraints.append(content.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor, constant: -pad))
            }

            root.view.addSubview(container)
            constraints.append(container.topAnchor.constraint(equalTo: root.view.topAnchor))
            constraints.append(container.leadingAnchor.constraint(equalTo: root.view.leadingAnchor))
            constraints.append(container.widthAnchor.constraint(equalToConstant: width))
            NSLayoutConstraint.activate(constraints)

            window.setNeedsLayout()
            window.layoutIfNeeded()

            let format = UIGraphicsImageRendererFormat()
            format.scale = 2
            let renderer = UIGraphicsImageRenderer(bounds: container.bounds, format: format)
            let image = renderer.image { ctx in
                container.layer.render(in: ctx.cgContext)
            }
            guard let png = image.pngData() else {
                XCTFail("failed to render \(fixture.name)")
                continue
            }
            try png.write(to: outDir.appendingPathComponent("\(fixture.name).png"))
        }
    }

    private func screenshotDir() -> URL {
        if let override = ProcessInfo.processInfo.environment["SCREENSHOT_OUT"] {
            return URL(fileURLWithPath: override)
        }
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("screenshots/ios")
    }
}
