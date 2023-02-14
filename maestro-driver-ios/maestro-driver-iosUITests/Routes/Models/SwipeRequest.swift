import Foundation

struct SwipeRequest: Decodable {

    enum CodingKeys: String, CodingKey {
        case startX, startY, endX, endY, duration
    }

    let start: CGPoint
    let end: CGPoint
    let duration: TimeInterval

    init(start: CGPoint, end: CGPoint, duration: Double) {
        self.start = start
        self.end = end
        self.duration = duration
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        start = CGPoint(
            x: try container.decode(Double.self, forKey: .startX),
            y: try container.decode(Double.self, forKey: .startY)
        )
        end = CGPoint(
            x: try container.decode(Double.self, forKey: .endX),
            y: try container.decode(Double.self, forKey: .endY)
        )
        self.duration = try container.decode(Double.self, forKey: .duration)
    }
}
