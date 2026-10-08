import XCTest
import UIKit
@testable import MessagesView

final class ImageLoadingTests: XCTestCase {
    private final class Loader: MessageImageLoader {
        let cancelled: XCTestExpectation
        init(_ cancelled: XCTestExpectation) { self.cancelled = cancelled }
        func load(_ url: URL) async throws -> UIImage {
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch { cancelled.fulfill(); throw error }
            return UIImage()
        }
    }
    @MainActor func testDetachedImageCancelsItsTask() async {
        let cancelled = expectation(description: "image cancelled")
        var reference = MessageImageReference(); reference.url = "https://example.test/image"; reference.alternateText = "Photo"
        var image = MessageImageContent(); image.image = reference
        var component = MessageComponent(); component.contents = .image(image)
        let view = MessageComponentView(component: component, imageLoader: Loader(cancelled))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        window.addSubview(view)
        await Task.yield()
        view.removeFromSuperview()
        await fulfillment(of: [cancelled], timeout: 3)
    }
    @MainActor func testAttachedActionHasAnAccessibleActivation() {
        var component = MessageComponent(); component.contents = .text(.with { $0.text = "Open" })
        component.action = MessageAction()
        var calls = 0
        let view = MessageComponentView(component: component, onAction: { _ in calls += 1 })
        let control = view.subviews.first?.accessibilityElements?.last as? UIAccessibilityElement
        XCTAssertTrue(control?.accessibilityActivate() == true)
        XCTAssertEqual(calls, 1)
    }

    @MainActor func testInlineActionIsActivatedAtItsUtf16Range() {
        var text = MessageText(); text.text = "😀 link"
        var inline = Com_Latenighthack_Social_Messages_V1_Inline(); inline.offset = 3; inline.length = 4
        var tappable = Com_Latenighthack_Social_Messages_V1_Inline.Rule.Tappable()
        tappable.action = MessageAction()
        inline.rule.contents = .tappable(tappable); text.inlines = [inline]
        var component = MessageComponent(); component.contents = .text(text)
        var calls = 0
        let view = MessageComponentView(component: component, onAction: { _ in calls += 1 })
        let label = view.subviews.first as! MessageActionLabel
        label.activate(offset: 4)
        XCTAssertEqual(calls, 1)
        label.activate(offset: 0)
        XCTAssertEqual(calls, 1)
    }
}
