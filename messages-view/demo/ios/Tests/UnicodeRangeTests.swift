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
}
