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
    case setOrientation
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
        let actualPort = port ?? 22087
        NSLog("[XCTestHTTPServer] PORT env var: %@", ProcessInfo.processInfo.environment["PORT"] ?? "nil")
        NSLog("[XCTestHTTPServer] Using port: %d", actualPort)
        let server = HTTPServer(address: try .inet(ip4: "0.0.0.0", port: actualPort), timeout: 100)
        
        for route in Route.allCases {
            let handler = await RouteHandlerFactory.createRouteHandler(route: route)
            await server.appendRoute(route.toHTTPRoute(), to: handler)
        }
        
        try await server.run()
    }
}
