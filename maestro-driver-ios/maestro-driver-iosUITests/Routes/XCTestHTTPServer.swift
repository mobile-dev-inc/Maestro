import FlyingFox

enum Route: String, CaseIterable {
    case subTree
    case runningApp
    case swipe
    case inputText
    case touch
    case screenshot
    case isScreenStatic
    
    func toHTTPRoute() -> HTTPRoute {
        return HTTPRoute(rawValue)
    }
}

struct XCTestHTTPServer {
    func start() async throws {
        let server = HTTPServer(port: 22087)
        
        for route in Route.allCases {
            let handler = RouteHandlerFactory.createRouteHandler(route: route)
            await server.appendRoute(route.toHTTPRoute(), to: handler)
        }
        
        try await server.start()
    }
}
