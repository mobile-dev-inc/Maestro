import Foundation

/// Represents the desired permission state
public enum PermissionValue: String, Codable, Equatable {
    case allow
    case deny
    case unset
    case unknown

    public init(from decoder: Decoder) throws {
        self = try PermissionValue(rawValue: decoder.singleValueContainer().decode(RawValue.self)) ?? .unknown
    }
}