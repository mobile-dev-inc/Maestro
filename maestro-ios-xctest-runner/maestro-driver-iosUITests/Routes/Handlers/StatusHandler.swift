import FlyingFox
import XCTest
import os

@MainActor
struct StatusHandler: HTTPHandler {
    
    private static let springboardBundleId = "com.apple.springboard"

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> HTTPResponse {
        do {
            let statusResponse = StatusResponse(status: String(describing: Status.ok))
            let responseBody = try JSONEncoder().encode(statusResponse)
            return HTTPResponse(statusCode: .ok, body: responseBody)
        }
        catch let error as AppError {
           return error.httpResponse
       } catch let error {
           return AppError(message: "Error in passing status \(error.localizedDescription)").httpResponse
       }
    }
}
