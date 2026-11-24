import Flutter
import PhotosUI
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    UIDevice.current.beginGeneratingDeviceOrientationNotifications()
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  // With the UIScene lifecycle (required from iOS 27), the window and its
  // FlutterViewController don't exist yet in didFinishLaunching. Flutter calls
  // this once the storyboard's engine is up; plugins and channels register here.
  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let messenger = engineBridge.applicationRegistrar.messenger()

    // Set up method channel for password test screen
    let passwordTestChannel = FlutterMethodChannel(
      name: "com.example.demo_app/password_test",
      binaryMessenger: messenger
    )

    passwordTestChannel.setMethodCallHandler { [weak self] (call, result) in
      if call.method == "openPasswordTest" {
        self?.openPasswordTestScreen()
        result(nil)
      } else {
        result(FlutterMethodNotImplemented)
      }
    }

    let deepMenuChannel = FlutterMethodChannel(
      name: "com.example.demo_app/deep_menu",
      binaryMessenger: messenger
    )

    deepMenuChannel.setMethodCallHandler { [weak self] (call, result) in
      if call.method == "openDeepMenu" {
        self?.present(DeepMenuViewController())
        result(nil)
      } else {
        result(FlutterMethodNotImplemented)
      }
    }

    let orientationChannel = FlutterMethodChannel(
      name: "com.example.demo_app/orientation",
      binaryMessenger: messenger
    )

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

    let healthAccessChannel = FlutterMethodChannel(
      name: "com.example.demo_app/health_access",
      binaryMessenger: messenger
    )

    healthAccessChannel.setMethodCallHandler { (call, result) in
      if call.method == "requestHealthAccess" {
        HealthAccessManager.requestAuthorization { success, error in
          if let error = error {
            result(FlutterError(code: "HEALTH_ERROR", message: error.localizedDescription, details: nil))
          } else {
            result(success)
          }
        }
      } else {
        result(FlutterMethodNotImplemented)
      }
    }

    // Crash test screen: ends the process for real, which Dart code cannot do by throwing.
    let crashChannel = FlutterMethodChannel(
      name: "com.example.demo_app/crash",
      binaryMessenger: messenger
    )

    crashChannel.setMethodCallHandler { (call, result) in
      switch call.method {
      case "crash": fatalError("demo_app: crash requested from the crash test screen")
      case "exit":  exit(0)
      default:      result(FlutterMethodNotImplemented)
      }
    }

    let photoPickerChannel = FlutterMethodChannel(
      name: "com.example.demo_app/photo_picker",
      binaryMessenger: messenger
    )

    photoPickerChannel.setMethodCallHandler { [weak self] (call, result) in
      switch call.method {
      case "pickWithImagePicker": self?.presentPhotoPicker(PhotoPicker.imagePicker(result))
      case "pickWithPHPicker":    self?.presentPhotoPicker(PhotoPicker.phPicker(result))
      default:                    result(FlutterMethodNotImplemented)
      }
    }
  }

  private func presentPhotoPicker(_ picker: UIViewController) {
    let rootViewController = UIApplication.shared.connectedScenes
      .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
      .first
    rootViewController?.present(picker, animated: true)
  }

  private func openPasswordTestScreen() {
    present(PasswordTestViewController())
  }

  private func present(_ viewController: UIViewController) {
    let rootViewController = UIApplication.shared.connectedScenes
      .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
      .first
    guard let rootViewController else { return }
    viewController.modalPresentationStyle = .fullScreen
    rootViewController.present(viewController, animated: true)
  }
}
/// Presents a native photo picker and reports to Flutter whether a photo was picked.
final class PhotoPicker: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate,
  PHPickerViewControllerDelegate {
  private static var current: PhotoPicker?
  private let result: FlutterResult

  private init(_ result: @escaping FlutterResult) {
    self.result = result
  }

  static func imagePicker(_ result: @escaping FlutterResult) -> UIViewController {
    let delegate = PhotoPicker(result)
    current = delegate
    let picker = UIImagePickerController()
    picker.sourceType = .photoLibrary
    picker.delegate = delegate
    return picker
  }

  static func phPicker(_ result: @escaping FlutterResult) -> UIViewController {
    let delegate = PhotoPicker(result)
    current = delegate
    var configuration = PHPickerConfiguration()
    configuration.filter = .images
    let picker = PHPickerViewController(configuration: configuration)
    picker.delegate = delegate
    return picker
  }

  private func finish(_ picker: UIViewController, picked: Bool) {
    picker.dismiss(animated: true)
    result(picked)
    PhotoPicker.current = nil
  }

  func imagePickerController(
    _ picker: UIImagePickerController,
    didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
  ) {
    finish(picker, picked: true)
  }

  func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
    finish(picker, picked: false)
  }

  func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
    finish(picker, picked: !results.isEmpty)
  }
}
