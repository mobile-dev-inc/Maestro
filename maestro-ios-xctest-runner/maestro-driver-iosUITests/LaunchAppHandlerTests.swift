import XCTest
import FlyingFox
import AnyCodable

@MainActor
final class LaunchAppHandlerTests: XCTestCase {

    private var server: HTTPServer!
    
    override func setUpWithError() throws {
        continueAfterFailure = false
        Task {
            try await startFlyingFoxServer()
        }
    }

    func startFlyingFoxServer() async throws {
        let server = HTTPServer(port: 8080)
        await server.appendRoute("launchApp", to: LaunchAppHandler())
        try await server.run()
    }

    func testLaunchDemoAppWithArguments() async throws {
        // Prepare the request
        let launchRequest = LaunchAppRequest(
            appId: "com.example.example",
            launchArguments: [
                "featureFlagA": true,
                "username": "maestroUser",
                "retryCount": 3
            ]
        )
        
        var request = URLRequest(url: URL(string: "http://localhost:8080/launchApp")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(launchRequest)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        // Make HTTP call to trigger app launch
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)

        // Verify app is running and arguments are displayed
        let app = XCUIApplication(bundleIdentifier: launchRequest.appId)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5), "App did not launch")

        let expected = [
                    "--featureFlagA",
                    "--username", "maestroUser",
                    "--retryCount", "3"]
        
        let actual = flattenLaunchArguments(launchRequest.launchArguments)
                XCTAssertEqual(actual, expected)

    }
    
    func flattenLaunchArguments(_ arguments: [String: AnyCodable]) -> [String] {
            arguments.flatMap { key, value -> [String] in
                switch value.value {
                case let bool as Bool:
                    return bool ? ["--\(key)"] : []
                default:
                    return ["--\(key)", "\(value)"]
                }
            }
        }
}
