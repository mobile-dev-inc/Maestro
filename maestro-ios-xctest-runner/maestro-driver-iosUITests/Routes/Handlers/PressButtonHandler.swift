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
        case .home:
            #if os(tvOS)
            XCUIRemote.shared.press(.home)
            #else
            XCUIDevice.shared.press(.home)
            #endif
        case .lock:
            #if os(tvOS)
            XCUIRemote.shared.press(.home)
            #else
            XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
            #endif
        #if os(tvOS)    
        case .up:
            XCUIRemote.shared.press(.up)
        case .down:
            XCUIRemote.shared.press(.down)
        case .left:
            XCUIRemote.shared.press(.left)
        case .right:
            XCUIRemote.shared.press(.right)
        case .select:
            XCUIRemote.shared.press(.select)
        case .menu:
            XCUIRemote.shared.press(.menu)
        case .playPause:
            XCUIRemote.shared.press(.playPause)
        #endif
        }
        return HTTPResponse(statusCode: .ok)
    }
}
