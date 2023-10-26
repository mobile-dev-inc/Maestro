import Foundation

struct StatusResponse: Codable {
    let status: String
}

enum Status: Codable {
    case ok
}
