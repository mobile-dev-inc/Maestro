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
            let springboardApplication = XCUIApplication(bundleIdentifier: StatusHandler.springboardBundleId)
            let snapshotDictionary = try springboardApplication.snapshot().dictionaryRepresentation
            let springboardHierarchy = AXElement(snapshotDictionary)
            let springBoardViewHierarchy = ViewHierarchy.init(axElement: springboardHierarchy, depth: springboardHierarchy.depth())
            let body = try JSONEncoder().encode(springBoardViewHierarchy)
            return HTTPResponse(statusCode: .ok, body: body)
        }
        catch let error as AppError {
           return error.httpResponse
       } catch let error {
           return AppError(message: "Snapshot failure while getting view hierarchy. Error: \(error.localizedDescription)").httpResponse
       }
    }
}
