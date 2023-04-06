import Foundation

struct TouchRequest : Codable {
    let x: Float
    let y: Float
    let duration: TimeInterval?
}
