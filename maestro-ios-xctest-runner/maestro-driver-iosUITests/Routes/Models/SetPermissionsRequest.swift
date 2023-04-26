import Foundation

enum PermissionValue: String, Codable {
    case allow
    case deny
    case unset
    case unknown
        
    init(from decoder: Decoder) throws {
        self = try PermissionValue(rawValue: decoder.singleValueContainer().decode(RawValue.self)) ?? .unknown
    }
}

struct SetPermissionsRequest: Codable {
    let permissions: [String : PermissionValue]
}
