
import Foundation

struct EraseTextRequest: Codable {
    let charactersToErase: Int
    let appIds: [String]
}
