import XCTest
import FlyingFox

class maestro_driver_iosUITests: XCTestCase {

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.

        // In UI tests it is usually best to stop immediately when a failure occurs.
        continueAfterFailure = false

        // In UI tests it’s important to set the initial state - such as interface orientation - required for your tests before they run. The setUp method is a good place to do this.
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    func testHttpServer() async throws {
        let server = HTTPServer(address: .loopback(port: 22087))
        let subTreeRoute = HTTPRoute(Route.subTree.rawValue)
        let runningAppRoute = HTTPRoute(method: .POST,
                                        path: Route.runningApp.rawValue)
        let swipeRoute = HTTPRoute(method: .POST,
                                   path: Route.swipe.rawValue)
        let inputTextRoute = HTTPRoute(method: .POST, path: Route.inputText.rawValue)
        let touchRoute = HTTPRoute(method: .POST, path: Route.touch.rawValue)
        let screenshotRoute = HTTPRoute(Route.screenshot.rawValue)
        let screenDiffRoute = HTTPRoute(Route.screenDiff.rawValue)
        await server.appendRoute(subTreeRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .subTree)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(runningAppRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .runningApp)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(swipeRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .swipe)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(inputTextRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .inputText)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(touchRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .touch)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(screenshotRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .screenshot)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(screenDiffRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .screenDiff)
            return try await handler.handle(request: request)

        }
        try await server.start()
    }
}
