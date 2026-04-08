import UIKit

class PasswordTestViewController: UIViewController {

    // MARK: - UI Components

    private let titleLabel: UILabel = {
        let label = UILabel()
        label.text = "Password Autofill Test"
        label.font = UIFont.boldSystemFont(ofSize: 24)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    private let descriptionLabel: UILabel = {
        let label = UILabel()
        label.text = "Tap the password field to test autofill behavior."
        label.font = UIFont.systemFont(ofSize: 14)
        label.textColor = .secondaryLabel
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    private let passwordField: UITextField = {
        let tf = UITextField()
        tf.placeholder = "Password"
        tf.borderStyle = .roundedRect
        tf.isSecureTextEntry = true
        tf.translatesAutoresizingMaskIntoConstraints = false
        tf.accessibilityIdentifier = "password_field"
        if #available(iOS 11.0, *) {
            tf.textContentType = .password
        }
        return tf
    }()

    private let backButton: UIButton = {
        let btn = UIButton(type: .system)
        btn.setTitle("Back to App", for: .normal)
        btn.backgroundColor = .systemGray
        btn.setTitleColor(.white, for: .normal)
        btn.layer.cornerRadius = 8
        btn.translatesAutoresizingMaskIntoConstraints = false
        btn.accessibilityIdentifier = "back_button"
        return btn
    }()

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        setupUI()
        setupActions()
        setupKeyboardDismissal()
    }

    // MARK: - Setup

    private func setupUI() {
        view.addSubview(titleLabel)
        view.addSubview(descriptionLabel)
        view.addSubview(passwordField)
        view.addSubview(backButton)

        let padding: CGFloat = 24

        NSLayoutConstraint.activate([
            titleLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: padding),
            titleLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            titleLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),

            descriptionLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 16),
            descriptionLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            descriptionLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),

            passwordField.topAnchor.constraint(equalTo: descriptionLabel.bottomAnchor, constant: 32),
            passwordField.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            passwordField.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),
            passwordField.heightAnchor.constraint(equalToConstant: 50),

            backButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -padding),
            backButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            backButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),
            backButton.heightAnchor.constraint(equalToConstant: 50),
        ])
    }

    private func setupActions() {
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
    }

    private func setupKeyboardDismissal() {
        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
    }

    // MARK: - Actions

    @objc private func backTapped() {
        dismiss(animated: true)
    }

    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }
}