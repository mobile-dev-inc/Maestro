import XCTest
import FlyingFox
import maestro_driver_ios

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
        let server = HTTPServer(address: .loopback(port: 9080))
        let subTreeRoute = HTTPRoute(Route.subTree.rawValue)
        let runningAppRoute = HTTPRoute(method: .POST,
                                        path: Route.runningApp.rawValue)
        await server.appendRoute(subTreeRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .subTree)
            return try await handler.handle(request: request)
        }
        await server.appendRoute(runningAppRoute) { request in
            let handler = RouteHandlerFactory.createRouteHandler(route: .runningApp)
            return try await handler.handle(request: request)
        }
        try await server.start()
    }
}
