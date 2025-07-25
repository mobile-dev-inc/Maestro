
import Foundation
import XCTest

struct PressButtonRequest: Codable {
    enum Button: String, Codable {
        case home
        case lock
    }

    let button: Button
}
