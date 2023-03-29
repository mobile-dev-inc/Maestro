import Foundation

struct SwipeRequest: Decodable {

    enum CodingKeys: String, CodingKey {
        case appId, startX, startY, endX, endY, duration
    }

    let appId: String?
    let start: CGPoint
    let end: CGPoint
    let duration: TimeInterval

    init(appId: String?, start: CGPoint, end: CGPoint, duration: Double) {
        self.appId = appId
        self.start = start
        self.end = end
        self.duration = duration
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        appId = try container.decode(String.self, forKey: .appId)
        start = CGPoint(
            x: try container.decode(Double.self, forKey: .startX),
            y: try container.decode(Double.self, forKey: .startY)
        )
        end = CGPoint(
            x: try container.decode(Double.self, forKey: .endX),
            y: try container.decode(Double.self, forKey: .endY)
        )
        duration = try container.decode(Double.self, forKey: .duration)
    }
}
