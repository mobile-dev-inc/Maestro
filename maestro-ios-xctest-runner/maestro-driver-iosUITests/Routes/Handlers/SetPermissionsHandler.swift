import Foundation
import FlyingFox
import os

@MainActor
struct SetPermissionsHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(SetPermissionsRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
        
        if let encoded = try? JSONEncoder().encode(requestBody.permissions) {
            UserDefaults.standard.set(encoded, forKey: "permissions")
            return HTTPResponse(statusCode: .ok)
        } else {
            let errorData = handleError(message: "failed to save permissions data")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
    }
    
    private func handleError(message: String) -> Data {
        logger.error("Failed to set app permissions - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
