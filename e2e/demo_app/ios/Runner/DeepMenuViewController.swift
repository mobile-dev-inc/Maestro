import UIKit

/// A native button with a UIMenu at the bottom of a view hierarchy deeper than the
/// driver's snapshot depth limit (60), so the driver has to rebuild the tree in parts.
class DeepMenuViewController: UIViewController {

    private let nestingDepth = 54

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        let closeButton = UIButton(type: .system)
        closeButton.setTitle("Close", for: .normal)
        closeButton.addAction(UIAction { [weak self] _ in self?.dismiss(animated: true) }, for: .touchUpInside)

        var container: UIView = view
        for level in 0..<nestingDepth {
            let child = UIView()
            child.accessibilityIdentifier = "level-\(level)"
            pin(child, in: container)
            container = child
        }

        let menuButton = UIButton(type: .system)
        menuButton.setTitle("Open menu", for: .normal)
        menuButton.showsMenuAsPrimaryAction = true
        menuButton.menu = UIMenu(children: [
            UIAction(title: "Menu item one") { _ in },
            UIAction(title: "Menu item two") { _ in },
        ])

        let stack = UIStackView(arrangedSubviews: [closeButton, menuButton])
        stack.axis = .vertical
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: container.centerYAnchor),
        ])
    }

    private func pin(_ child: UIView, in parent: UIView) {
        child.translatesAutoresizingMaskIntoConstraints = false
        parent.addSubview(child)
        NSLayoutConstraint.activate([
            child.topAnchor.constraint(equalTo: parent.safeAreaLayoutGuide.topAnchor),
            child.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
            child.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
            child.trailingAnchor.constraint(equalTo: parent.trailingAnchor),
        ])
    }
}
