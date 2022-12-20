import FlyingFox
import XCTest
import os

class SubTreeRouteHandler: RouteHandler {
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "SubTreeRouteHandler")
    
    func handle(request: HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let appId = request.query["appId"] else {
            logger.error("Requested view hierarchy for an invalid appId")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest)
        }
        
        let viewHierarchyHttpResponse = await MainActor.run { () -> HTTPResponse in
            do {
                logger.info("Trying to capture hierarchy snapshot for \(appId)")
                let start = NSDate().timeIntervalSince1970 * 1000
                let xcuiApplication = XCUIApplication(bundleIdentifier: appId)
                let springboardApplication = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                if appId != "dev.mobile.maestro-driver-iosUITests.xctrunner" && springboardApplication.buttons["Allow"].exists {
                    xcuiApplication.tap()
                }
                logger.info("Now trying hierarchy")
                let viewHierarchyDictionary = try xcuiApplication.snapshot().dictionaryRepresentation
                let end = NSDate().timeIntervalSince1970 * 1000
                logger.info("Successfully got view hierarchy for \(appId) in \(end - start)")
                let hierarchyJsonData = try JSONSerialization.data(
                    withJSONObject: viewHierarchyDictionary,
                    options: .prettyPrinted
                )
                return HTTPResponse(statusCode: .ok, body: hierarchyJsonData)
            } catch let error {
                let message = error.localizedDescription
                logger.error("Snapshot failure, cannot return view hierarchy due to \(message)")
                let errorCode = getErrorCode(message: message)
                let errorJson = """
                 { "errorMessage" : "Snapshot failure while getting view hierarchy", "errorCode": "\(errorCode)" }
                """
                return HTTPResponse(statusCode: .badRequest, body:  Data(errorJson.utf8))
            }
        }
        return viewHierarchyHttpResponse
    }
    
    private func getErrorCode(message: String) -> String {
        if message.contains("Error kAXErrorIllegalArgument getting snapshot for element") {
           return "illegal-argument-snapshot-failure"
        } else {
           return "unknown-snapshot-failure"
        }
    }
}
