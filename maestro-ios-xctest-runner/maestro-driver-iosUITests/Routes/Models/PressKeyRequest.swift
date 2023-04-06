import Foundation
import XCTest

struct PressKeyRequest: Codable {
    enum Key: String, Codable {
        case delete
        case `return`
        case enter
        case tab
        case space
        case escape
    }

    let key: Key

    var xctestKey: String {
        // Note: XCUIKeyboardKey().rawValue is the ascii representation of that key,
        // not the enum value name.
        switch key {
        case .delete: return XCUIKeyboardKey.delete.rawValue
        case .return: return XCUIKeyboardKey.return.rawValue
        case .enter: return XCUIKeyboardKey.enter.rawValue
        case .tab: return XCUIKeyboardKey.tab.rawValue
        case .space: return XCUIKeyboardKey.space.rawValue
        case .escape: return XCUIKeyboardKey.escape.rawValue
        }
    }
}
