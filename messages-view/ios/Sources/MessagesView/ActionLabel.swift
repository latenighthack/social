import UIKit

final class MessageActionLabel: UILabel, UIGestureRecognizerDelegate {
    var actions: [(NSRange, MessageAction)] = []
    var onAction: MessageActionHandler?
    var redactions: [NSRange] = []
    var icons: [(NSTextAttachment, URL)] = []
    var imageLoader: MessageImageLoader = URLSessionMessageImageLoader.shared
    private var loading: [Task<Void, Never>] = []
    override init(frame: CGRect) {
        super.init(frame: frame)
        let tap = UITapGestureRecognizer(target: self, action: #selector(activate(_:)))
        tap.delegate = self
        addGestureRecognizer(tap)
        isUserInteractionEnabled = true
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
    @discardableResult func activate(offset: Int) -> Bool {
        guard !redactions.contains(where: { NSLocationInRange(offset, $0) }),
              let action = actions.first(where: { NSLocationInRange(offset, $0.0) })?.1 else { return false }
        onAction?(action)
        return true
    }
    func actionOffset(at point: CGPoint) -> Int? {
        guard let attributedText = attributedText else { return nil }
        let storage = NSTextStorage(attributedString: attributedText)
        let paragraph = NSMutableParagraphStyle(); paragraph.alignment = textAlignment
        storage.addAttribute(.paragraphStyle, value: paragraph, range: NSRange(location: 0, length: storage.length))
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: bounds.size)
        container.lineFragmentPadding = 0; container.maximumNumberOfLines = numberOfLines
        container.lineBreakMode = lineBreakMode
        layout.addTextContainer(container); storage.addLayoutManager(layout)
        let used = layout.usedRect(for: container)
        let verticalOffset = (bounds.height - used.height) / 2 - used.minY
        let local = CGPoint(x: point.x - bounds.minX, y: point.y - bounds.minY - verticalOffset)
        guard used.contains(local) else { return nil }
        let index = layout.characterIndex(for: local, in: container, fractionOfDistanceBetweenInsertionPoints: nil)
        guard actions.contains(where: { NSLocationInRange(index, $0.0) }),
              !redactions.contains(where: { NSLocationInRange(index, $0) }) else { return nil }
        return index
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        actionOffset(at: touch.location(in: self)) != nil
    }
    @objc private func activate(_ gesture: UITapGestureRecognizer) {
        if let offset = actionOffset(at: gesture.location(in: self)) { activate(offset: offset) }
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
