struct SetPickerValueRequest: Codable {
    let value: String
    let wheelIndex: Int?
    let waitToSettleTimeoutMs: Int?
    let appIds: [String]
}
