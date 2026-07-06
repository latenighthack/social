import UIKit

/// The single-line text preview used in a room/conversation list row.
public final class MessagePreviewView: UIView {
    private let label = UILabel()

    public init(component: MessageComponent?, theme: MessageTheme = .preview, prefix: String = "", emptyText: String = "No messages yet") {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        label.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 1
        label.lineBreakMode = .byTruncatingTail
        label.font = .systemFont(ofSize: theme.defaultTextSize)
        label.textColor = theme.textColor

        if let component = component, let text = MessagePreviewView.findPreviewText(component) {
            label.text = prefix + text
        } else {
            label.text = emptyText
            label.font = .italicSystemFont(ofSize: theme.defaultTextSize)
        }

        addSubview(label)
        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: topAnchor),
            label.bottomAnchor.constraint(equalTo: bottomAnchor),
            label.leadingAnchor.constraint(equalTo: leadingAnchor),
            label.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    /// Depth-first search for renderable preview text: prefer the first Text node ordered
    /// DEFAULT > TITLE > SUBTITLE > DESCRIPTION; fall back to an image's alternate text.
    public static func findPreviewText(_ component: MessageComponent) -> String? {
        var texts: [MessageText] = []
        var images: [String] = []
        collect(component, texts: &texts, images: &images)
        if !texts.isEmpty {
            let order: [MessageText.Style] = [.default, .title, .subtitle, .description_]
            for style in order {
                if let match = texts.first(where: { $0.style == style && !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
                    return match.text.trimmingCharacters(in: .whitespaces)
                }
            }
            if let any = texts.first(where: { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
                return any.text.trimmingCharacters(in: .whitespaces)
            }
        }
        return images.first
    }

    private static func collect(_ component: MessageComponent, texts: inout [MessageText], images: inout [String]) {
        switch component.contents {
        case .text(let text):
            texts.append(text)
        case .image(let image):
            let alt = image.image.alternateText
            images.append(alt.isEmpty ? "Photo" : alt)
        case .container(let container):
            for child in container.children {
                collect(child, texts: &texts, images: &images)
            }
        default:
            break
        }
    }
}
