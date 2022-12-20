import FlyingFox

protocol RouteHandler {
    func handle(request: HTTPRequest) async throws -> HTTPResponse
}
