import Foundation

struct ViewHierarchyRequest: Codable {
    @available(*, deprecated, message: "This field is no longer used and will be removed in a future version")
    let appIds: [String]?
    let excludeKeyboardElements: Bool
}
