import UIKit

final class MessageActionLabel: UILabel {
    var actions: [(NSRange, MessageAction)] = []
    var onAction: MessageActionHandler?
    var redactions: [NSRange] = []
    var icons: [(NSTextAttachment, URL)] = []
    var imageLoader: MessageImageLoader = URLSessionMessageImageLoader.shared
    private var loading: [Task<Void, Never>] = []
    override init(frame: CGRect) {
        super.init(frame: frame)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(activate(_:))))
        isUserInteractionEnabled = true
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
    @discardableResult func activate(offset: Int) -> Bool {
        guard !redactions.contains(where: { NSLocationInRange(offset, $0) }),
              let action = actions.first(where: { NSLocationInRange(offset, $0.0) })?.1 else { return false }
        onAction?(action)
        return true
    }
    @objc private func activate(_ gesture: UITapGestureRecognizer) {
        guard let attributedText = attributedText else { return }
        let storage = NSTextStorage(attributedString: attributedText)
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: bounds.size)
        container.lineFragmentPadding = 0; container.maximumNumberOfLines = numberOfLines
        container.lineBreakMode = lineBreakMode
        layout.addTextContainer(container); storage.addLayoutManager(layout)
        let point = gesture.location(in: self)
        guard layout.usedRect(for: container).contains(point) else { return }
        let index = layout.characterIndex(for: point, in: container, fractionOfDistanceBetweenInsertionPoints: nil)
        activate(offset: index)
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        loading.forEach { $0.cancel() }; loading.removeAll()
        guard window != nil else { icons.forEach { $0.0.image = nil }; return }
        for (attachment, url) in icons {
            let loader = imageLoader
            loading.append(Task { [weak self] in
                guard let image = try? await loader.load(url), !Task.isCancelled else { return }
                attachment.image = image
                guard let self = self else { return }
                self.attributedText = self.attributedText?.copy() as? NSAttributedString
            })
        }
    }
    deinit { loading.forEach { $0.cancel() } }
}
