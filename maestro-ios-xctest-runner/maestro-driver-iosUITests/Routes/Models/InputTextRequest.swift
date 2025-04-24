struct InputTextRequest: Codable {
    let text: String
    @available(*, deprecated, message: "This field is no longer used and will be removed in a future version")
    let appIds: [String]?
}
