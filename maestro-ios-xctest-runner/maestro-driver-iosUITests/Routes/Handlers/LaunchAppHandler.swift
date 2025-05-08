import Foundation
import XCTest
import FlyingFox
import os

@MainActor
struct LaunchAppHandler: HTTPHandler {
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        // Decode request body to extract appId and optional launch arguments
        guard let requestBody = try? await JSONDecoder().decode(LaunchAppRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "Incorrect request body for launching app").httpResponse
        }
        
        let app = XCUIApplication(bundleIdentifier: requestBody.bundleId)

        // Set launch arguments if available
        if let arguments = requestBody.arguments {
            app.launchArguments.append(contentsOf: arguments)
        }

        // Set environment variables if available
        if let environment = requestBody.environment {
            for (key, value) in environment {
                app.launchEnvironment[key] = value
            }
        }

        NSLog("[Start] Launching app \(requestBody.bundleId) with arguments: \(app.launchArguments) and environment: \(app.launchEnvironment)")

        do {
            app.launch()

            NSLog("[End] Successfully launched app \(requestBody.bundleId)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            NSLog("[Error] Failed to launch app \(requestBody.bundleId): \(error.localizedDescription)")
            return AppError(
                type: .internal,
                message: "Failed to launch app. Error: \(error.localizedDescription)"
            ).httpResponse
        }
    }
}
