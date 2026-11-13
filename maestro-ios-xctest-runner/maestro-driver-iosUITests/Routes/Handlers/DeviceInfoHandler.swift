import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct DeviceInfoHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        do {
            let (width, height) = try screenSize()

            // The main screen's scale serves every screen: a foldable's screens share one density, and
            // XCUIScreen exposes none short of taking a screenshot.
            let deviceInfo = DeviceInfoResponse(
                widthPoints: Int(width),
                heightPoints: Int(height),
                widthPixels: Int(CGFloat(width) * UIScreen.main.scale),
                heightPixels: Int(CGFloat(height) * UIScreen.main.scale)
            )

            let responseBody = try JSONEncoder().encode(deviceInfo)
            return HTTPResponse(statusCode: .ok, body: responseBody)
        } catch let error {
            return AppError(message: "Getting device info call failed. Error \(error.localizedDescription)").httpResponse
        }
    }

    private func screenSize() throws -> (Float, Float) {
        if let display = ActiveDisplay.current() {
            return (Float(display.uprightSize.width), Float(display.uprightSize.height))
        }
        let (width, height, orientation) = try ScreenSizeHelper.actualScreenSize()
        NSLog("Device orientation is \(String(orientation.rawValue))")
        return (width, height)
    }
}
