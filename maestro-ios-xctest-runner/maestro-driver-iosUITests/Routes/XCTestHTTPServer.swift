import FlyingFox
import Foundation

enum Route: String, CaseIterable {
    case runningApp
    case swipe
    case swipeV2
    case inputText
    case touch
    case screenshot
    case isScreenStatic
    case pressKey
    case pressButton
    case eraseText
    case deviceInfo
    case setPermissions
    case viewHierarchy
    case status
    case keyboard
    case launchApp
    case terminateApp

    func toHTTPRoute() -> HTTPRoute {
        return HTTPRoute(rawValue)
    }
}

struct XCTestHTTPServer {
    func start() async throws {
        let port = ProcessInfo.processInfo.environment["PORT"]?.toUInt16()
        let server = HTTPServer(address: try .inet(ip4: "127.0.0.1", port: port ?? 22087), timeout: 100)
        
        for route in Route.allCases {
            let handler = await RouteHandlerFactory.createRouteHandler(route: route)
            await server.appendRoute(route.toHTTPRoute(), to: handler)
        }
        
        try await server.run()
    }
}
