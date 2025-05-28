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
            AXClientProxy.load()
            let screenshot1 = RunningApp.getForegroundApp()!.screenshot()
            let screenshot2 = RunningApp.getForegroundApp()!.screenshot()
            let hash1 = SHA256.hash(data: screenshot1.pngRepresentation)
            let hash2 = SHA256.hash(data: screenshot2.pngRepresentation)
            

            let isScreenStatic = screenshot1.pngRepresentation == screenshot2.pngRepresentation
            
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
