import UIKit

final class GalleryViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.06, green: 0.055, blue: 0.09, alpha: 1)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 12
        stack.alignment = .fill
        stack.isLayoutMarginsRelativeArrangement = true
        stack.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
        ])

        for fixture in Fixtures.load(bundle: .main) {
            let row = UIView()
            let content = Fixtures.render(fixture)
            content.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(content)
            var constraints = [
                content.topAnchor.constraint(equalTo: row.topAnchor),
                content.bottomAnchor.constraint(equalTo: row.bottomAnchor),
            ]
            if fixture.mode == "message" && !fixture.incoming {
                constraints.append(content.trailingAnchor.constraint(equalTo: row.trailingAnchor))
                constraints.append(content.leadingAnchor.constraint(greaterThanOrEqualTo: row.leadingAnchor))
            } else {
                constraints.append(content.leadingAnchor.constraint(equalTo: row.leadingAnchor))
                constraints.append(content.trailingAnchor.constraint(lessThanOrEqualTo: row.trailingAnchor))
            }
            NSLayoutConstraint.activate(constraints)
            stack.addArrangedSubview(row)
        }
    }
}
