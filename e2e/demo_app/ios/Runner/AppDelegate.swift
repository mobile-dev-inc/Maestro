import Flutter
import PhotosUI
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

    let photoPickerChannel = FlutterMethodChannel(
      name: "com.example.demo_app/photo_picker",
      binaryMessenger: controller.binaryMessenger
    )

    photoPickerChannel.setMethodCallHandler { [weak self] (call, result) in
      if call.method == "openPhotoPicker" {
        if #available(iOS 14, *) {
          self?.openPhotoPicker()
          result(nil)
        } else {
          result(FlutterError(code: "UNSUPPORTED", message: "PHPickerViewController requires iOS 14+", details: nil))
        }
      } else {
        result(FlutterMethodNotImplemented)
      }
    }

    let orientationChannel = FlutterMethodChannel(
      name: "com.example.demo_app/orientation",
      binaryMessenger: controller.binaryMessenger
    )

    UIDevice.current.beginGeneratingDeviceOrientationNotifications()

    orientationChannel.setMethodCallHandler { (call, result) in
      if call.method == "getOrientation" {
        switch UIDevice.current.orientation {
        case .portrait:             result("Portrait")
        case .portraitUpsideDown:   result("Portrait Upside Down")
        case .landscapeLeft:        result("Landscape Left")
        case .landscapeRight:       result("Landscape Right")
        default:                    result("Unknown")
        }
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

  @available(iOS 14, *)
  private func openPhotoPicker() {
    guard let rootViewController = window?.rootViewController else { return }
    var configuration = PHPickerConfiguration()
    configuration.filter = .images
    configuration.selectionLimit = 1
    let picker = PHPickerViewController(configuration: configuration)
    picker.delegate = self
    rootViewController.present(picker, animated: true)
  }
}

@available(iOS 14, *)
extension AppDelegate: PHPickerViewControllerDelegate {
  func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
    picker.dismiss(animated: true)
  }
}