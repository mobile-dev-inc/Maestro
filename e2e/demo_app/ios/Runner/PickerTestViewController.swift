import UIKit

/// Native iOS view controller hosting a real UIPickerView, used by the
/// setPickerValue e2e test. CupertinoPicker on iOS doesn't render as a
/// native UIPickerView, so XCTest's pickerWheels query can't find it —
/// this controller provides a real native picker for the e2e test target.
class PickerTestViewController: UIViewController, UIPickerViewDataSource, UIPickerViewDelegate {
    private static let countries: [String] = [
        "Afghanistan", "Albania", "Algeria", "Argentina", "Australia",
        "Belgium", "Brazil", "Canada", "Chile", "China",
        "Denmark", "Egypt", "France", "Germany", "Greece",
        "India", "Ireland", "Italy", "Japan", "Kenya",
        "Mexico", "Netherlands", "Norway", "Peru", "Portugal",
        "Spain", "Sweden", "Switzerland", "Turkey", "United Kingdom",
        "United States", "Vietnam",
    ]

    // MARK: - UI Components

    private let titleLabel: UILabel = {
        let label = UILabel()
        label.text = "Picker Test"
        label.font = UIFont.boldSystemFont(ofSize: 24)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    private let selectedLabel: UILabel = {
        let label = UILabel()
        label.text = "Selected: Afghanistan"
        label.font = UIFont.systemFont(ofSize: 18)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        label.accessibilityIdentifier = "selected_country_label"
        return label
    }()

    private let pickerView: UIPickerView = {
        let pv = UIPickerView()
        pv.translatesAutoresizingMaskIntoConstraints = false
        pv.accessibilityIdentifier = "country_picker"
        return pv
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
        pickerView.dataSource = self
        pickerView.delegate = self
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
    }

    private func setupUI() {
        view.addSubview(titleLabel)
        view.addSubview(selectedLabel)
        view.addSubview(pickerView)
        view.addSubview(backButton)

        let padding: CGFloat = 24

        NSLayoutConstraint.activate([
            titleLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: padding),
            titleLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            titleLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),

            selectedLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 16),
            selectedLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            selectedLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),

            pickerView.topAnchor.constraint(equalTo: selectedLabel.bottomAnchor, constant: 16),
            pickerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            pickerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            pickerView.bottomAnchor.constraint(equalTo: backButton.topAnchor, constant: -16),

            backButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -padding),
            backButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: padding),
            backButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -padding),
            backButton.heightAnchor.constraint(equalToConstant: 50),
        ])
    }

    @objc private func backTapped() {
        dismiss(animated: true)
    }

    // MARK: - UIPickerViewDataSource

    func numberOfComponents(in _: UIPickerView) -> Int { 1 }

    func pickerView(_: UIPickerView, numberOfRowsInComponent _: Int) -> Int {
        Self.countries.count
    }

    // MARK: - UIPickerViewDelegate

    func pickerView(_: UIPickerView, titleForRow row: Int, forComponent _: Int) -> String? {
        Self.countries[row]
    }

    func pickerView(_: UIPickerView, didSelectRow row: Int, inComponent _: Int) {
        selectedLabel.text = "Selected: \(Self.countries[row])"
    }
}
