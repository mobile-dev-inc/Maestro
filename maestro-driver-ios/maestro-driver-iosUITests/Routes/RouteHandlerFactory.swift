import Foundation

class RouteHandlerFactory {
    class func createRouteHandler(route: Route) -> RouteHandler {
        switch route {
        case .subTree:
            return SubTreeRouteHandler()
        case .runningApp:
            return RunningAppRouteHandler()
        }
    }
}
