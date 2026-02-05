import Foundation

struct DragRequest: Decodable {

    enum CodingKeys: String, CodingKey {
        case appId, startX, startY, endX, endY, duration
    }

    let appId: String?
    let start: CGPoint?
    let end: CGPoint?
    let duration: TimeInterval

    init(appId: String?, start: CGPoint?, end: CGPoint?, duration: Double) {
        self.appId = appId
        self.start = start
        self.end = end
        self.duration = duration
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        appId = try container.decodeIfPresent(String.self, forKey: .appId)

        if let startX = try container.decodeIfPresent(Double.self, forKey: .startX),
           let startY = try container.decodeIfPresent(Double.self, forKey: .startY) {
            start = CGPoint(x: startX, y: startY)
        } else {
            start = nil
        }

        if let endX = try container.decodeIfPresent(Double.self, forKey: .endX),
           let endY = try container.decodeIfPresent(Double.self, forKey: .endY) {
            end = CGPoint(x: endX, y: endY)
        } else {
            end = nil
        }

        duration = try container.decode(Double.self, forKey: .duration)
    }
}
