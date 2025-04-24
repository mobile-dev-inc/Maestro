
import Foundation

struct EraseTextRequest: Codable {
    let charactersToErase: Int
    @available(*, deprecated, message: "This field is no longer used and will be removed in a future version")
    let appIds: [String]?
}
