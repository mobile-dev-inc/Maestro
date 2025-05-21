import Foundation
import XCTest
import FlyingFox
import os

@MainActor
struct LaunchAppHandler: HTTPHandler {
    
    private let logger = Logger(
           subsystem: Bundle.main.bundleIdentifier!,
           category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        // Decode request body to extract appId and optional launch arguments
        guard let requestBody = try? await JSONDecoder().decode(LaunchAppRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "Incorrect request body for launching app").httpResponse
        }
        
        let app = XCUIApplication(bundleIdentifier: requestBody.appId)

        // Set launch arguments if available
        let arguments = requestBody.launchArguments
        let flattenedArgs = arguments.flatMap { key, value -> [String] in
            return ["--\(key)", "\(value)"]
        }
        app.launchArguments.append(contentsOf: flattenedArgs)


        NSLog("[Start] Launching app \(requestBody.appId) with arguments: \(app.launchArguments) and environment: \(app.launchEnvironment)")

        do {
            app.launch()

            NSLog("[End] Successfully launched app \(requestBody.appId)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            NSLog("[Error] Failed to launch app \(requestBody.appId): \(error.localizedDescription)")
            return AppError(
                type: .internal,
                message: "Failed to launch app. Error: \(error.localizedDescription)"
            ).httpResponse
        }
    }
}
