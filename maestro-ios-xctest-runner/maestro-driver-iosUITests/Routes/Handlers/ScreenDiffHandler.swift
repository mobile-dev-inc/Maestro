import Foundation
import XCTest
import CryptoKit
import FlyingFox
import os

@MainActor
struct IsScreenStaticHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        do {
            let screen = ActiveDisplay.current()?.screen ?? XCUIScreen.main
            let screenshot1 = screen.screenshot()
            let screenshot2 = screen.screenshot()
            let hash1 = SHA256.hash(data: screenshot1.pngRepresentation)
            let hash2 = SHA256.hash(data: screenshot2.pngRepresentation)
            
            let isScreenStatic = hash1 == hash2
            
            let response = ["isScreenStatic" : isScreenStatic]
            
            let responseData = try JSONSerialization.data(
                withJSONObject: response,
                options: .prettyPrinted
            )
            return HTTPResponse(statusCode: .ok, body: responseData)
        } catch let error {
            return AppError(message: "Detecting screen static request failed. Error \(error.localizedDescription)").httpResponse
        }
    }
}
