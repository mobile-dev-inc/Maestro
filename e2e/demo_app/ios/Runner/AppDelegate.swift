import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)

    // Set up method channel for password test screen
    let controller = window?.rootViewController as! FlutterViewController
    let passwordTestChannel = FlutterMethodChannel(
      name: "com.example.demo_app/password_test",
      binaryMessenger: controller.binaryMessenger
    )

    passwordTestChannel.setMethodCallHandler { [weak self] (call, result) in
      if call.method == "openPasswordTest" {
        self?.openPasswordTestScreen()
        result(nil)
      } else {
        result(FlutterMethodNotImplemented)
      }
    }

    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  private func openPasswordTestScreen() {
    guard let rootViewController = window?.rootViewController else { return }
    let passwordTestVC = PasswordTestViewController()
    passwordTestVC.modalPresentationStyle = .fullScreen
    rootViewController.present(passwordTestVC, animated: true)
  }
}