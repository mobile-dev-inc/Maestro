import Foundation

struct ViewHierarchyRequest: Codable {
    let appIds: [String]
    let excludeKeyboardElements: Bool
}
