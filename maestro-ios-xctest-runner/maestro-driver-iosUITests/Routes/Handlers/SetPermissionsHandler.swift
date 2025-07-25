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
        guard let requestBody = try? await JSONDecoder().decode(SetPermissionsRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for set permissions").httpResponse
        }
        
        do {
            let permissionsMap = try JSONEncoder().encode(requestBody.permissions)
            UserDefaults.standard.set(permissionsMap, forKey: "permissions")
            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return AppError(message: "Failure in setting permissions. Error: \(error.localizedDescription)").httpResponse
        }
    }
}
