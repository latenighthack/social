import XCTest
import UIKit
import MessagesView

final class RedactionTests: XCTestCase {
    @MainActor func testPreviewAndAccessibilityConcealRedaction() {
        var text = MessageText()
        text.text = "Before secret after"
        var inline = Com_Latenighthack_Social_Messages_V1_Inline()
        inline.offset = 7; inline.length = 6
        inline.rule.contents = .redaction(.init())
        text.inlines = [inline]
        var component = MessageComponent(); component.contents = .text(text)
        XCTAssertEqual(MessagePreviewView.findPreviewText(component), "Before ██████ after")
        let rendered = MessageComponentView(component: component)
        func labels(_ view: UIView) -> [UILabel] {
            (view as? UILabel).map { [$0] } ?? view.subviews.flatMap { labels($0) }
        }
        XCTAssertEqual(labels(rendered).first?.accessibilityLabel, "Before ██████ after")
    }
}
