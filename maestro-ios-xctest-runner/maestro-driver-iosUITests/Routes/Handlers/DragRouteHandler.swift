import FlyingFox
import XCTest
import os
import Foundation

@MainActor
struct DragRouteHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let requestBody: DragRequest
        do {
            requestBody = try await JSONDecoder().decode(DragRequest.self, from: request.bodyData)
        } catch {
            return AppError(
                type: .precondition,
                message: "incorrect request body provided for drag request: \(error)"
            ).httpResponse
        }

        do {
            // Prefer element-based drag if text selectors are provided
            if let fromText = requestBody.fromText, let toText = requestBody.toText {
                try await dragByText(
                    fromText: fromText,
                    toText: toText,
                    toOffsetX: requestBody.toOffsetX ?? 0,
                    toOffsetY: requestBody.toOffsetY ?? 0,
                    duration: requestBody.duration)
            } else if let start = requestBody.start, let end = requestBody.end {
                try await dragPrivateAPI(
                    start: start,
                    end: end,
                    duration: requestBody.duration)
            } else {
                return AppError(
                    type: .precondition,
                    message: "Drag request requires either coordinates or text selectors"
                ).httpResponse
            }

            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return AppError(message: "Drag request failure. Error: \(error.localizedDescription)").httpResponse
        }
    }

    /// Finds an element by text, ensuring it has valid absolute screen coordinates
    /// Returns the element with valid frame, or nil if none found
    private func findElementWithValidFrame(app: XCUIApplication, text: String, minValidY: CGFloat = 50) -> XCUIElement? {
        let predicate = NSPredicate(format: "label MATCHES %@", text)
        let matchingElements = app.staticTexts.matching(predicate)
        let count = matchingElements.count

        NSLog("[DRAG] Finding element with text '%@' - found %d candidates", text, count)

        // Iterate through all matching elements to find one with valid coordinates
        for i in 0..<count {
            let element = matchingElements.element(boundBy: i)
            if element.exists {
                let frame = element.frame
                NSLog("[DRAG] Candidate %d frame: %@", i, String(describing: frame))

                // Valid frame should have reasonable Y coordinate (not at top of screen)
                // and reasonable size. Relative coords typically show y=0 or very small y
                if frame.origin.y >= minValidY || frame.height > 30 {
                    NSLog("[DRAG] Found valid element at index %d", i)
                    return element
                }
            }
        }

        // If no staticText had valid coords, try other element types
        let allMatching = app.descendants(matching: .any).matching(predicate)
        let allCount = allMatching.count

        NSLog("[DRAG] Checking %d descendants for valid frames", allCount)

        for i in 0..<allCount {
            let element = allMatching.element(boundBy: i)
            if element.exists {
                let frame = element.frame
                if frame.origin.y >= minValidY || frame.height > 30 {
                    NSLog("[DRAG] Found valid descendant at index %d with frame %@", i, String(describing: frame))
                    return element
                }
            }
        }

        return nil
    }

    /// Drag using XCUIElement text queries - finds elements by text and uses their coordinates
    /// toOffsetX and toOffsetY are pixel offsets applied to the target element's center
    func dragByText(fromText: String, toText: String, toOffsetX: Double, toOffsetY: Double, duration: Double) async throws {
        logger.info("[DRAG] dragByText called: from='\(fromText)' to='\(toText)'")

        guard let app = RunningApp.getForegroundApp() else {
            logger.error("[DRAG] ERROR: No foreground app found")
            throw NSError(domain: "DragRouteHandler", code: 1, userInfo: [NSLocalizedDescriptionKey: "No foreground app found"])
        }

        // Force accessibility hierarchy refresh by querying the app
        _ = app.descendants(matching: .any).count

        // Find elements with valid screen coordinates
        guard let fromElement = findElementWithValidFrame(app: app, text: fromText) else {
            logger.error("[DRAG] ERROR: Could not find fromElement with valid frame for text: \(fromText)")
            throw NSError(domain: "DragRouteHandler", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not find element with valid coordinates for text: \(fromText)"])
        }

        guard let toElement = findElementWithValidFrame(app: app, text: toText) else {
            logger.error("[DRAG] ERROR: Could not find toElement with valid frame for text: \(toText)")
            throw NSError(domain: "DragRouteHandler", code: 3, userInfo: [NSLocalizedDescriptionKey: "Could not find element with valid coordinates for text: \(toText)"])
        }

        let fromFrame = fromElement.frame
        let toFrame = toElement.frame
        NSLog("[DRAG] Final fromElement frame: %@", String(describing: fromFrame))
        NSLog("[DRAG] Final toElement frame: %@", String(describing: toFrame))

        // Use XCUIElement's native drag API
        let fromCoord = fromElement.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let toCoord = toElement.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: toOffsetX, dy: toOffsetY))

        // Calculate velocity based on duration (points per second)
        let distance = hypot(toFrame.midX - fromFrame.midX, toFrame.midY - fromFrame.midY)
        let velocity = max(50, distance / CGFloat(duration))

        NSLog("[DRAG] Executing drag with velocity: %f pts/sec", velocity)

        fromCoord.press(forDuration: 0.1, thenDragTo: toCoord, withVelocity: XCUIGestureVelocity(velocity), thenHoldForDuration: 0.1)

        try await Task.sleep(nanoseconds: 250_000_000) // 250ms
        logger.info("[DRAG] Drag completed")
    }

    /// Uses XCUICoordinate's built-in press and drag API
    func dragWithXCUICoordinate(start: CGPoint, end: CGPoint, duration: Double) async throws {
        logger.info("Drag (XCUICoordinate) from \(start.debugDescription) to \(end.debugDescription) with \(duration) duration")

        guard let app = RunningApp.getForegroundApp() else {
            logger.error("No foreground app found for drag")
            throw NSError(domain: "DragRouteHandler", code: 1, userInfo: [NSLocalizedDescriptionKey: "No foreground app found"])
        }

        // Create coordinates relative to the app window
        // We use normalized offset (0,0) to get the origin, then offset by absolute points
        let startCoord = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: start.x, dy: start.y))
        let endCoord = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: end.x, dy: end.y))

        // Use the built-in press and drag API with velocity control
        // Press duration of 0.5s, slow velocity (100 points/sec), hold at end for 0.1s
        startCoord.press(forDuration: 0.5, thenDragTo: endCoord, withVelocity: 100, thenHoldForDuration: 0.1)

        // Allow time for iOS to fully process the gesture
        try await Task.sleep(nanoseconds: 250_000_000) // 250ms
        logger.info("Drag completed")
    }

    /// Fallback using synthesized events
    func dragPrivateAPI(start: CGPoint, end: CGPoint, duration: Double) async throws {
        logger.info("Drag from \(start.debugDescription) to \(end.debugDescription) with \(duration) duration")

        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.addDragEvent(start: start, end: end, duration: duration)

        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)

        // Allow time for iOS to fully process the gesture before accepting new ones
        try await Task.sleep(nanoseconds: 250_000_000) // 250ms
        logger.info("Drag completed")
    }
}
