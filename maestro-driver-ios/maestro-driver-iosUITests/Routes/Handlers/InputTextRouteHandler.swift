import FlyingFox
import XCTest
import os

class InputTextRouteHandler : RouteHandler {
    private enum Constants {
        // 15 characters per second
        static let typingFrequency = 15
        static let maxTextLength = 45
    }
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "InputTextRouteHandler")

    func handle(request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(InputTextRequest.self, from: request.body) else {
            return errorResponse(message: "incorrect request body provided")
        }

        do {
            if (requestBody.text.count > Constants.maxTextLength) {
                return fallbackOnCopyPaste(text: requestBody.text, request: request)
            } else {
                try await send(text: requestBody.text)
                return HTTPResponse(statusCode: .ok)
            }
        } catch {
            return errorResponse(message: "internal error")
        }
    }
    
    private func fallbackOnCopyPaste(text: String, request: FlyingFox.HTTPRequest) -> FlyingFox.HTTPResponse {
        guard let appId = request.query["appId"] else {
            logger.error("Requested view hierarchy for an invalid appId")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest)
        }
        
        let xcuiApplication = XCUIApplication(bundleIdentifier: appId)
        
        let element = xcuiApplication
            .descendants(matching: .any)
            .element(matching: NSPredicate(format: "hasKeyboardFocus == true"))
        
        if (!element.exists) {
            return HTTPResponse(statusCode: .notFound)
        }
        
        element.setText(text: text, application: xcuiApplication)
        
        return HTTPResponse(statusCode: .ok)
    }

    private func send(text: String) async throws {
        return try await withCheckedThrowingContinuation { continuation in
            let ObjC = ObjCRuntime()
            let daemonSession = ObjC.XCTRunnerDaemonSession.sharedSession()
            let proxy = daemonSession.daemonProxy().handle as! NSObject

            // ObjCRuntime does not work correctly because
            // class_getInstanceMethod(object_getClass(proxy), "_XCT_sendString:maximumFrequency:completion:") returns null
            // while proxy.method(for: NSSelectorFromString("_XCT_sendString:maximumFrequency:completion:")) does work
            //
            //            let proxy = daemonSession.daemonProxy()
            //            proxy._XCT_sendString(requestBody.text, maximumFrequency: 1, completion: { (error: Error?) in
            //                if let error = error {
            //                    continuation.resume(with: .failure(error))
            //                } else {
            //                    continuation.resume(with: .success(HTTPResponse(statusCode: .ok)))
            //                }
            //            })

            typealias sendStringMethod = @convention(c) (NSObject, Selector, NSString, Int, @escaping (Error?) -> ()) -> ()

            let selector = NSSelectorFromString("_XCT_sendString:maximumFrequency:completion:")
            let methodIMP = proxy.method(for: selector)

            logger.info("typing frequency: \(Constants.typingFrequency)")
            let method = unsafeBitCast(methodIMP, to: sendStringMethod.self)
            let start = Date()
            method(proxy, selector, text as NSString, Constants.typingFrequency, { error in
                if let error = error {
                    self.logger.error("Error inputting text '\(text)': \(error)")
                    continuation.resume(with: .failure(error))
                } else {
                    let duration = Date().timeIntervalSince(start)
                    self.logger.info("Text input duration took \(duration)")
                    continuation.resume(with: .success(()))
                }
            })
        }
    }

    private func errorResponse(message: String) -> HTTPResponse {
        logger.error("Failed to input text - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        let errorData = Data(jsonString.utf8)
        return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
    }
    
}
