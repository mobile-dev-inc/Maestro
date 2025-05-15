import AnyCodable

struct LaunchAppRequest: Codable {
    let appId: String
    let launchArguments: [String: AnyCodable]
}
