import UIKit

public typealias MessageActionHandler = (MessageAction) -> Void

/// A UIView that renders a single message's Component tree. Point it at a `MessageComponent`
/// and a `MessageTheme`; it builds the native subview hierarchy and sizes to its content.
public final class MessageComponentView: UIView {
    public init(component: MessageComponent, theme: MessageTheme = .incoming, onAction: MessageActionHandler? = nil) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        let renderer = MessageRenderer(theme: theme, onAction: onAction)
        let content = renderer.build(component, inOverlay: false, axis: .vertical, textAlign: .natural)
        content.translatesAutoresizingMaskIntoConstraints = false
        addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: topAnchor),
            content.leadingAnchor.constraint(equalTo: leadingAnchor),
            content.trailingAnchor.constraint(equalTo: trailingAnchor),
            content.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

/// Builds UIViews for a Component tree. Ported/expanded from the reference client's partial
/// UIKit renderer to cover the full messages.v1.Component set.
struct MessageRenderer {
    let theme: MessageTheme
    let onAction: MessageActionHandler?

    func build(_ component: MessageComponent, inOverlay: Bool, axis: NSLayoutConstraint.Axis, textAlign: NSTextAlignment) -> UIView {
        switch component.contents {
        case .container(let container):
            return buildContainer(component, container, inOverlay: inOverlay, textAlign: textAlign)
        case .text(let text):
            return buildText(text, inOverlay: inOverlay, textAlign: textAlign)
        case .image(let image):
            return buildImage(image)
        case .button(let button):
            return buildButton(component, button)
        case .divider:
            return buildDivider(axis: axis)
        case .none:
            return UIView()
        }
    }

    // MARK: containers

    private func buildContainer(_ component: MessageComponent, _ container: MessageContainer, inOverlay: Bool, textAlign: NSTextAlignment) -> UIView {
        switch container.contents {
        case .verticalStack(let stack):
            let align = textAlignment(stack.alignment)
            return verticalStack(container.children, inOverlay: inOverlay, textAlign: align)
        case .box:
            return verticalStack(container.children, inOverlay: inOverlay, textAlign: textAlign)
        case .horizontalStack(let stack):
            return horizontalStack(container.children, inOverlay: inOverlay, alignment: stackAlignment(stack.alignment))
        case .bubble:
            return buildBubble(container, inOverlay: inOverlay)
        case .overlay:
            return buildOverlay(container)
        case .quote:
            return buildQuote(container, inOverlay: inOverlay)
        case .grid(let grid):
            return buildGrid(container, grid, inOverlay: inOverlay)
        case .none:
            return UIView()
        }
    }

    private func verticalStack(_ children: [MessageComponent], inOverlay: Bool, textAlign: NSTextAlignment) -> UIStackView {
        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 2
        for child in children {
            stack.addArrangedSubview(build(child, inOverlay: inOverlay, axis: .vertical, textAlign: textAlign))
        }
        return stack
    }

    private func horizontalStack(_ children: [MessageComponent], inOverlay: Bool, alignment: UIStackView.Alignment) -> UIStackView {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.alignment = alignment
        stack.spacing = 6
        for child in children {
            stack.addArrangedSubview(build(child, inOverlay: inOverlay, axis: .horizontal, textAlign: .natural))
        }
        return stack
    }

    private func buildBubble(_ container: MessageContainer, inOverlay: Bool) -> UIView {
        // Pin the child directly to the bubble (not through a stack): a UILabel inside a stack
        // inside a width-hugging bubble computes an ambiguous intrinsic height, whereas a label
        // pinned straight to a `<= maxWidth` bubble resolves cleanly.
        let inner: UIView
        if container.children.count == 1 {
            inner = build(container.children[0], inOverlay: inOverlay, axis: .vertical, textAlign: .natural)
        } else {
            inner = verticalStack(container.children, inOverlay: inOverlay, textAlign: .natural)
        }
        inner.translatesAutoresizingMaskIntoConstraints = false

        let bubble = UIView()
        bubble.backgroundColor = theme.bubbleColor
        bubble.layer.cornerRadius = theme.bubbleRadius
        bubble.layer.masksToBounds = true
        bubble.addSubview(inner)
        NSLayoutConstraint.activate([
            inner.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 8),
            inner.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -8),
            inner.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 14),
            inner.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -14),
            bubble.widthAnchor.constraint(lessThanOrEqualToConstant: theme.bubbleMaxWidth),
        ])
        return bubble
    }

    private func buildOverlay(_ container: MessageContainer) -> UIView {
        let content = verticalStack(container.children, inOverlay: true, textAlign: .natural)
        content.isLayoutMarginsRelativeArrangement = true
        content.layoutMargins = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        content.translatesAutoresizingMaskIntoConstraints = false

        let host = UIView()
        let scrim = UIView()
        scrim.backgroundColor = UIColor(white: 0, alpha: 0.38)
        scrim.layer.cornerRadius = 12
        scrim.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(scrim)
        host.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: host.topAnchor),
            content.bottomAnchor.constraint(equalTo: host.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: host.trailingAnchor),
            scrim.topAnchor.constraint(equalTo: host.topAnchor),
            scrim.bottomAnchor.constraint(equalTo: host.bottomAnchor),
            scrim.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            scrim.trailingAnchor.constraint(equalTo: host.trailingAnchor),
        ])
        return host
    }

    private func buildQuote(_ container: MessageContainer, inOverlay: Bool) -> UIView {
        let content = verticalStack(container.children, inOverlay: inOverlay, textAlign: .natural)
        content.translatesAutoresizingMaskIntoConstraints = false

        let host = UIView()
        let bar = UIView()
        bar.backgroundColor = theme.dividerColor
        bar.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(bar)
        host.addSubview(content)
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            bar.topAnchor.constraint(equalTo: host.topAnchor, constant: 2),
            bar.bottomAnchor.constraint(equalTo: host.bottomAnchor, constant: -2),
            bar.widthAnchor.constraint(equalToConstant: 3),
            content.leadingAnchor.constraint(equalTo: host.leadingAnchor, constant: 14),
            content.trailingAnchor.constraint(equalTo: host.trailingAnchor),
            content.topAnchor.constraint(equalTo: host.topAnchor),
            content.bottomAnchor.constraint(equalTo: host.bottomAnchor),
        ])
        return host
    }

    private func buildGrid(_ container: MessageContainer, _ grid: Com_Latenighthack_Social_Messages_V1_Container.Grid, inOverlay: Bool) -> UIView {
        let cols = max(grid.columns.count, 1)
        let rows = UIStackView()
        rows.axis = .vertical
        rows.alignment = .fill
        rows.spacing = 0

        let children = container.children
        var index = 0
        var rowIndex = 0
        while index < children.count {
            let row = UIStackView()
            row.axis = .horizontal
            row.distribution = .fillEqually
            row.alignment = .fill
            row.spacing = 8
            row.isLayoutMarginsRelativeArrangement = true
            row.layoutMargins = UIEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
            for _ in 0..<cols {
                if index < children.count {
                    row.addArrangedSubview(build(children[index], inOverlay: inOverlay, axis: .vertical, textAlign: .natural))
                    index += 1
                } else {
                    row.addArrangedSubview(UIView())
                }
            }
            let wrapper = UIView()
            if grid.style == .striped && rowIndex % 2 == 1 {
                wrapper.backgroundColor = UIColor(white: 1, alpha: 0.04)
            }
            row.translatesAutoresizingMaskIntoConstraints = false
            wrapper.addSubview(row)
            NSLayoutConstraint.activate([
                row.topAnchor.constraint(equalTo: wrapper.topAnchor),
                row.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor),
                row.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                row.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
            ])
            rows.addArrangedSubview(wrapper)
            rowIndex += 1
        }
        return rows
    }

    // MARK: leaves

    private func buildText(_ text: MessageText, inOverlay: Bool, textAlign: NSTextAlignment) -> UIView {
        let label = UILabel()
        label.numberOfLines = 0
        label.textAlignment = textAlign
        // A multiline UILabel has no intrinsic width on its own, so a bubble that hugs it is
        // ambiguous (the solver squeezes it narrow and tall). Pin the wrap width to the bubble's
        // content width; narrower parents (grid cells) still wrap tighter via their own constraints.
        label.preferredMaxLayoutWidth = max(0, theme.bubbleMaxWidth - 28)
        label.setContentCompressionResistancePriority(.required, for: .vertical)
        let color = textColor(for: text.style, inOverlay: inOverlay)
        let font = self.font(for: text.style)
        if text.inlines.isEmpty {
            label.text = text.text
            label.textColor = color
            label.font = font
        } else {
            label.attributedText = attributedString(text, baseColor: color, baseFont: font)
        }
        switch text.style {
        case .title:
            label.numberOfLines = 1
            label.lineBreakMode = .byTruncatingTail
        case .subtitle:
            label.numberOfLines = 2
            label.lineBreakMode = .byTruncatingTail
        default:
            break
        }
        return label
    }

    private func buildImage(_ image: MessageImageContent) -> UIView {
        let ref = image.image
        let imageView = UIImageView()
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.backgroundColor = UIColor(argb: ref.previewColor)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        let aspect = ref.aspectRatio > 0 ? CGFloat(ref.aspectRatio) : 1

        switch image.style {
        case .small:
            return fixedImage(imageView, width: 64, height: 64, corner: 8)
        case .circular:
            return fixedImage(imageView, width: 64, height: 64, corner: 32)
        case .medium:
            return fixedImage(imageView, width: 160, height: 160 / aspect, corner: 10)
        case .square:
            imageView.layer.cornerRadius = 12
            imageView.heightAnchor.constraint(equalTo: imageView.widthAnchor).isActive = true
            return imageView
        default:
            imageView.layer.cornerRadius = 12
            imageView.heightAnchor.constraint(equalTo: imageView.widthAnchor, multiplier: 1 / aspect).isActive = true
            return imageView
        }
    }

    private func fixedImage(_ imageView: UIImageView, width: CGFloat, height: CGFloat, corner: CGFloat) -> UIView {
        imageView.layer.cornerRadius = corner
        let wrapper = UIView()
        wrapper.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
            imageView.topAnchor.constraint(equalTo: wrapper.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor),
            imageView.trailingAnchor.constraint(lessThanOrEqualTo: wrapper.trailingAnchor),
            imageView.widthAnchor.constraint(equalToConstant: width),
            imageView.heightAnchor.constraint(equalToConstant: height),
        ])
        return wrapper
    }

    private func buildButton(_ component: MessageComponent, _ button: MessageButton) -> UIView {
        let control = UIButton(type: .system)
        control.setTitle(button.text, for: .normal)
        control.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        control.heightAnchor.constraint(equalToConstant: 44).isActive = true

        switch button.style {
        case .cta:
            control.backgroundColor = theme.ctaBackgroundColor
            control.setTitleColor(theme.ctaTextColor, for: .normal)
            control.layer.cornerRadius = 10
        case .pill:
            control.setTitleColor(theme.buttonTextColor, for: .normal)
            control.layer.cornerRadius = 22
        default:
            control.setTitleColor(theme.buttonTextColor, for: .normal)
        }

        if component.hasAction, let handler = onAction {
            let action = component.action
            control.addAction(UIAction { _ in handler(action) }, for: .touchUpInside)
        }

        if button.style == .grouped {
            let line = UIView()
            line.backgroundColor = theme.dividerColor
            line.translatesAutoresizingMaskIntoConstraints = false
            let wrapper = UIView()
            control.translatesAutoresizingMaskIntoConstraints = false
            wrapper.addSubview(line)
            wrapper.addSubview(control)
            NSLayoutConstraint.activate([
                line.topAnchor.constraint(equalTo: wrapper.topAnchor),
                line.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                line.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
                line.heightAnchor.constraint(equalToConstant: 1),
                control.topAnchor.constraint(equalTo: line.bottomAnchor),
                control.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                control.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
                control.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor),
            ])
            return wrapper
        }
        return control
    }

    private func buildDivider(axis: NSLayoutConstraint.Axis) -> UIView {
        let view = UIView()
        view.backgroundColor = theme.dividerColor
        if axis == .horizontal {
            // Inside a horizontal stack -> a vertical rule.
            view.widthAnchor.constraint(equalToConstant: 1).isActive = true
            view.heightAnchor.constraint(equalToConstant: 16).isActive = true
        } else {
            view.heightAnchor.constraint(equalToConstant: 1).isActive = true
        }
        return view
    }

    // MARK: text styling

    private func attributedString(_ text: MessageText, baseColor: UIColor, baseFont: UIFont) -> NSAttributedString {
        let string = NSMutableAttributedString(
            string: text.text,
            attributes: [.foregroundColor: baseColor, .font: baseFont]
        )
        let full = text.text as NSString
        for inline in text.inlines {
            let start = max(0, min(Int(inline.offset), full.length))
            let length = max(0, min(Int(inline.length), full.length - start))
            if length == 0 { continue }
            let range = NSRange(location: start, length: length)
            guard let rule = inline.rule.contents else { continue }
            switch rule {
            case .bold:
                string.addAttribute(.font, value: bold(baseFont), range: range)
            case .italic:
                string.addAttribute(.font, value: italic(baseFont), range: range)
            case .strikethrough:
                string.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: range)
            case .tappable:
                string.addAttribute(.foregroundColor, value: theme.linkColor, range: range)
                string.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: range)
            case .userLink:
                string.addAttribute(.foregroundColor, value: theme.linkColor, range: range)
                string.addAttribute(.font, value: bold(baseFont), range: range)
            case .redaction:
                string.addAttribute(.backgroundColor, value: theme.redactionColor, range: range)
                string.addAttribute(.foregroundColor, value: UIColor.clear, range: range)
            case .icon:
                break
            }
        }
        return string
    }

    private func textColor(for style: MessageText.Style, inOverlay: Bool) -> UIColor {
        if inOverlay { return theme.overlayTextColor }
        switch style {
        case .title: return theme.titleColor
        case .subtitle: return theme.subtitleColor
        case .description_: return theme.descriptionColor
        default: return theme.textColor
        }
    }

    private func font(for style: MessageText.Style) -> UIFont {
        switch style {
        case .title: return .systemFont(ofSize: theme.titleTextSize, weight: .bold)
        case .subtitle: return .systemFont(ofSize: theme.subtitleTextSize)
        case .description_: return .systemFont(ofSize: theme.descriptionTextSize)
        default: return .systemFont(ofSize: theme.defaultTextSize)
        }
    }

    private func bold(_ font: UIFont) -> UIFont {
        let descriptor = font.fontDescriptor.withSymbolicTraits(font.fontDescriptor.symbolicTraits.union(.traitBold)) ?? font.fontDescriptor
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }

    private func italic(_ font: UIFont) -> UIFont {
        let descriptor = font.fontDescriptor.withSymbolicTraits(font.fontDescriptor.symbolicTraits.union(.traitItalic)) ?? font.fontDescriptor
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }

    private func textAlignment(_ alignment: Com_Latenighthack_Social_Messages_V1_Container.HorizontalAlignment) -> NSTextAlignment {
        switch alignment {
        case .left: return .left
        case .centerHorizontal: return .center
        case .right: return .right
        default: return .natural
        }
    }

    private func stackAlignment(_ alignment: Com_Latenighthack_Social_Messages_V1_Container.VerticalAlignment) -> UIStackView.Alignment {
        switch alignment {
        case .centerVertical: return .center
        case .bottom: return .bottom
        default: return .top
        }
    }
}
