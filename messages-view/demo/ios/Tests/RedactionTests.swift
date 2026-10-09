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
    @MainActor func testRedactedInlineActionHasNoAccessibleActivation() {
        var text = MessageText(); text.text = "secret"
        var hidden = Com_Latenighthack_Social_Messages_V1_Inline()
        hidden.offset = 0; hidden.length = 6; hidden.rule.contents = .redaction(.init())
        var link = hidden
        var tappable = Com_Latenighthack_Social_Messages_V1_Inline.Rule.Tappable()
        tappable.action = MessageAction(); link.rule.contents = .tappable(tappable)
        text.inlines = [link, hidden]
        var component = MessageComponent(); component.contents = .text(text)
        let view = MessageComponentView(component: component)
        let label = view.subviews.first as? UILabel
        XCTAssertEqual(label?.accessibilityCustomActions?.count, 0)
    }
}
