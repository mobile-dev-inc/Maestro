struct LaunchAppRequest: Codable {
    let bundleId: String
    let arguments: [String]?
    let environment: [String: String]?
}
