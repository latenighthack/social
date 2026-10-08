import Foundation

public enum MessageInlineRanges {
    public static func range(_ source: String, offset: Int32, length: Int32) -> NSRange {
        let units = Array(source.utf16)
        var start = max(0, min(Int(offset), units.count))
        guard length > 0 else { return NSRange(location: start, length: 0) }
        var end = max(start, min(Int(offset) + Int(length), units.count))
        guard end > start else { return NSRange(location: start, length: 0) }
        func high(_ value: UInt16) -> Bool { (0xd800...0xdbff).contains(value) }
        func low(_ value: UInt16) -> Bool { (0xdc00...0xdfff).contains(value) }
        if start > 0 && start < units.count && low(units[start]) && high(units[start - 1]) { start -= 1 }
        if end > 0 && end < units.count && low(units[end]) && high(units[end - 1]) { end += 1 }
        return NSRange(location: start, length: end - start)
    }
}
