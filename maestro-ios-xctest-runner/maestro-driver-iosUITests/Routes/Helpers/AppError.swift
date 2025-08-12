
import Foundation
import FlyingFox

enum AppErrorType: String, Codable {
    case `internal`
    case precondition
    case timeout
}

struct AppError: Error, Codable {
    let type: AppErrorType
    let message: String

    private var statusCode: HTTPStatusCode {
        switch type {
        case .internal: return .internalServerError
        case .precondition: return .badRequest
        case .timeout: return .requestTimeout
        }
    }

    var httpResponse: HTTPResponse {
        let body = try? JSONEncoder().encode(self)
        return HTTPResponse(statusCode: statusCode, body: body ?? Data())
    }

    init(type: AppErrorType = .internal, message: String) {
        self.type = type
        self.message = message
    }

    private enum CodingKeys : String, CodingKey {
        case type = "code"
        case message = "errorMessage"
    }
}
