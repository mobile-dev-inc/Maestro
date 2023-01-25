import FlyingFox
import XCTest
import os

class InputTextRouteHandler : RouteHandler {
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "InputTextRouteHandler")

    func handle(request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(InputTextRequest.self, from: request.body) else {
            return errorResponse(message: "incorrect request body provided")
        }

        do {
            try await send(text: requestBody.text)
            return HTTPResponse(statusCode: .ok)
        } catch {
            return errorResponse(message: "internal error")
        }
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

            let typingFrequency = typingFrequencyFor(text: text)
            logger.info("typing frequency: \(typingFrequency)")
            let method = unsafeBitCast(methodIMP, to: sendStringMethod.self)
            let start = Date()
            method(proxy, selector, text as NSString, typingFrequency, { error in
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

    private func typingFrequencyFor(text: String) -> Int {
        let count = max(text.count, 5)
        return count * 2
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
