import Foundation

enum PermissionValue: String, Codable {
    case allow
    case deny
    case unset
}

struct SetPermissionsRequest: Codable {
    let permissions: [String : PermissionValue]
}
