
import Foundation
import XCTest

struct PressButtonRequest: Codable {
    enum Button: String, Codable {
        case home
        case lock
        #if os(tvOS)
        case up
        case down
        case left
        case right
        case select
        case menu
        case playPause
        #endif
    }

    let button: Button
}
