import UIKit

/// Visual configuration for the renderer. Colours/sizes are supplied here rather than pulled
/// from a global, so the library stays a pure, importable component. Palette matches the
/// web/Android renderers so screenshots line up.
public struct MessageTheme {
    public var textColor: UIColor
    public var titleColor: UIColor
    public var subtitleColor: UIColor
    public var descriptionColor: UIColor
    public var overlayTextColor: UIColor
    public var linkColor: UIColor
    public var redactionColor: UIColor
    public var bubbleColor: UIColor
    public var dividerColor: UIColor

    public var defaultTextSize: CGFloat
    public var titleTextSize: CGFloat
    public var subtitleTextSize: CGFloat
    public var descriptionTextSize: CGFloat

    public var bubbleRadius: CGFloat
    public var bubbleMaxWidth: CGFloat

    public var buttonTextColor: UIColor
    public var ctaTextColor: UIColor
    public var ctaBackgroundColor: UIColor

    private static let white70 = UIColor(white: 1, alpha: 0.7)
    private static let white60 = UIColor(white: 1, alpha: 0.6)
    private static let white50 = UIColor(white: 1, alpha: 0.5)
    private static let white15 = UIColor(white: 1, alpha: 0.15)
    private static let link = UIColor(rgb: 0x8E84FA)
    private static let redaction = UIColor(white: 0, alpha: 0.85)

    private static func base(text: UIColor, title: UIColor, subtitle: UIColor, description: UIColor, bubble: UIColor) -> MessageTheme {
        MessageTheme(
            textColor: text,
            titleColor: title,
            subtitleColor: subtitle,
            descriptionColor: description,
            overlayTextColor: .white,
            linkColor: link,
            redactionColor: redaction,
            bubbleColor: bubble,
            dividerColor: white15,
            defaultTextSize: 16,
            titleTextSize: 18,
            subtitleTextSize: 15,
            descriptionTextSize: 13,
            bubbleRadius: 18,
            bubbleMaxWidth: 260,
            buttonTextColor: link,
            ctaTextColor: .white,
            ctaBackgroundColor: link
        )
    }

    /// Message received from someone else: neutral dark bubble.
    public static let incoming = base(text: .white, title: .white, subtitle: white70, description: white50, bubble: UIColor(rgb: 0x262532))

    /// Our own message: accent bubble.
    public static let outgoing = base(text: .white, title: .white, subtitle: white70, description: white50, bubble: UIColor(rgb: 0x7924FF))

    /// Compact preview used in a room/conversation list row.
    public static let preview = base(text: white60, title: white60, subtitle: white60, description: white60, bubble: .clear)
}

extension UIColor {
    convenience init(rgb: Int) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }

    /// Packed ARGB int (as carried in ImageReference.preview_color).
    convenience init(argb: Int32) {
        let value = UInt32(bitPattern: argb)
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: value > 0xFFFFFF ? CGFloat((value >> 24) & 0xFF) / 255 : 1
        )
    }
}
