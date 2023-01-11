struct SwipeRequest: Codable {
    let startX: Float
    let startY: Float
    let endX: Float
    let endY: Float
    let velocity: Float?
}
