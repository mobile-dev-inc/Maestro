//
//  ViewController.swift
//  maestro-driver-ios
//
//
//

import UIKit

class ViewController: UIViewController {

    private let statusLabel: UILabel = {
        let label = UILabel()
        label.textAlignment = .center
        label.numberOfLines = 0
        label.font = .systemFont(ofSize: 18, weight: .semibold)
        label.text = "NETWORK_UNKNOWN"
        label.isAccessibilityElement = true
        label.accessibilityIdentifier = "networkStatusLabel"
        label.accessibilityLabel = label.text
        return label
    }()

    private let detailsLabel: UILabel = {
        let label = UILabel()
        label.textAlignment = .center
        label.numberOfLines = 0
        label.font = .systemFont(ofSize: 13, weight: .regular)
        label.textColor = .secondaryLabel
        label.text = "Tap “Check network” to run a request to https://example.com"
        label.isAccessibilityElement = true
        label.accessibilityIdentifier = "networkDetailsLabel"
        label.accessibilityLabel = label.text
        return label
    }()

    private lazy var checkButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("Check network", for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.backgroundColor = .systemBlue
        button.contentEdgeInsets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
        button.layer.cornerRadius = 10
        button.clipsToBounds = true
        button.addTarget(self, action: #selector(didTapCheck), for: .touchUpInside)
        button.isAccessibilityElement = true
        button.accessibilityIdentifier = "checkNetworkButton"
        button.accessibilityLabel = "Check network"
        return button
    }()

    private var timer: Timer?
    private var requestInFlight = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        let stack = UIStackView(arrangedSubviews: [statusLabel, detailsLabel, checkButton])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        detailsLabel.translatesAutoresizingMaskIntoConstraints = false
        checkButton.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            statusLabel.widthAnchor.constraint(lessThanOrEqualToConstant: 320),
            detailsLabel.widthAnchor.constraint(lessThanOrEqualToConstant: 320),
        ])

        timer = Timer.scheduledTimer(timeInterval: 1.0, target: self, selector: #selector(runCheck), userInfo: nil, repeats: true)
        runCheck()
    }

    deinit {
        timer?.invalidate()
    }

    @objc private func didTapCheck() {
        runCheck()
    }

    @objc private func runCheck() {
        guard !requestInFlight else { return }
        requestInFlight = true

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 1.0
        config.timeoutIntervalForResource = 1.0
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config)

        let url = URL(string: "https://example.com/?t=\(UUID().uuidString)")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let start = Date()
        session.dataTask(with: request) { [weak self] _, response, error in
            guard let self else { return }
            defer {
                self.requestInFlight = false
            }

            let elapsedMs = Int(Date().timeIntervalSince(start) * 1000)

            let online: Bool
            if let http = response as? HTTPURLResponse {
                online = (200...399).contains(http.statusCode)
            } else {
                online = false
            }

            let statusText = online ? "NETWORK_ONLINE" : "NETWORK_OFFLINE"
            let detailText: String
            if let error {
                detailText = "Error: \(error.localizedDescription) (\(elapsedMs)ms)"
            } else if let http = response as? HTTPURLResponse {
                detailText = "HTTP \(http.statusCode) (\(elapsedMs)ms)"
            } else {
                detailText = "No response (\(elapsedMs)ms)"
            }

            DispatchQueue.main.async {
                self.statusLabel.text = statusText
                self.statusLabel.accessibilityLabel = statusText
                self.detailsLabel.text = detailText
                self.detailsLabel.accessibilityLabel = detailText
            }
        }.resume()
    }

}
