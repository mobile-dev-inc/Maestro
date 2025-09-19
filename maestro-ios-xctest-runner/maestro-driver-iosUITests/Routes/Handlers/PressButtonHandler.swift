import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct PressButtonHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(PressButtonRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "Incorrect request body for PressButton Handler").httpResponse
        }
        
        switch requestBody.button {
            #if !os(tvOS)
            case .home:
                XCUIDevice.shared.press(.home)
            case .lock:
                XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
            #else
            case .volumeUp:
                if #available(tvOS 14.3, *) {
                    XCUIRemote.shared.press(XCUIRemote.Button.pageUp)
                }
            case .volumeDown:
                if #available(tvOS 14.3, *) {
                    XCUIRemote.shared.press(XCUIRemote.Button.pageDown)
                }
            case .remoteDpadUp:
                XCUIRemote.shared.press(XCUIRemote.Button.up)
            case .remoteDpadDown:
                XCUIRemote.shared.press(XCUIRemote.Button.down)
            case .remoteDpadLeft:
                XCUIRemote.shared.press(XCUIRemote.Button.left)
            case .remoteDpadRight:
                XCUIRemote.shared.press(XCUIRemote.Button.right)
            case .remoteDpadCenter:
                XCUIRemote.shared.press(XCUIRemote.Button.select)
            case .remoteMediaPlayPause:
                XCUIRemote.shared.press(XCUIRemote.Button.playPause)
            case .RemoteMenu:
                XCUIRemote.shared.press(XCUIRemote.Button.menu)
            #endif
        }
        return HTTPResponse(statusCode: .ok)
    }
}
