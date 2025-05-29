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
    
    func delay(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        do {
            let screenshot1 = logger.measure(message: "Screenshot one") {
                XCUIScreen.main.screenshot()
            }
            logger.log("[Delay] before")
            await delay(2)
            logger.log("[Delay] after")


            let screenshot2 = logger.measure(message: "Screenshot two") {
                XCUIScreen.main.screenshot()
            }
            
            
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
