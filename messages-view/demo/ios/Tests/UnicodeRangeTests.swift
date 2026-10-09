import XCTest
import UIKit
import MessagesView

final class UnicodeRangeTests: XCTestCase {
    func testSharedBinaryFixtureUsesUtf16Offsets() throws {
        let fixture = try XCTUnwrap(Fixtures.load(bundle: Bundle(for: Self.self)).first { $0.name == "unicode-rich" })
        let component = try MessageComponent(serializedBytes: fixture.bytes)
        XCTAssertEqual(MessagePreviewView.findPreviewText(component), "😀 café é ██████")
        XCTAssertEqual(MessageInlineRanges.range("😀x", offset: 1, length: 1), NSRange(location: 0, length: 2))
        XCTAssertEqual(MessageInlineRanges.range("😀x", offset: 1, length: 0), NSRange(location: 1, length: 0))
    }
    @MainActor func testOverlappingBoldAndItalicRulesComposeTheirTraits() {
        var text = MessageText(); text.text = "abc"
        var bold = Com_Latenighthack_Social_Messages_V1_Inline()
        bold.offset = 0; bold.length = 3; bold.rule.contents = .bold(.init())
        var italic = Com_Latenighthack_Social_Messages_V1_Inline()
        italic.offset = 1; italic.length = 1; italic.rule.contents = .italic(.init())
        text.inlines = [bold, italic]
        var component = MessageComponent(); component.contents = .text(text)
        let view = MessageComponentView(component: component)
        let label = view.subviews.first as? UILabel
        let middle = label?.attributedText?.attribute(.font, at: 1, effectiveRange: nil) as? UIFont
        XCTAssertTrue(middle?.fontDescriptor.symbolicTraits.contains([.traitBold, .traitItalic]) == true)
        let first = label?.attributedText?.attribute(.font, at: 0, effectiveRange: nil) as? UIFont
        XCTAssertTrue(first?.fontDescriptor.symbolicTraits.contains(.traitBold) == true)
        XCTAssertFalse(first?.fontDescriptor.symbolicTraits.contains(.traitItalic) == true)
    }
}
