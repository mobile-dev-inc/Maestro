import FlyingFox
import XCTest
import os

@MainActor
struct SetPickerValueRouteHandler: HTTPHandler {
    private static let defaultPickerExistenceTimeoutMs = 2000

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(SetPickerValueRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for setPickerValue").httpResponse
        }

        do {
            let start = Date()

            guard let app = RunningApp.getForegroundApp() else {
                return AppError(message: "No foreground app to query for picker wheel").httpResponse
            }

            let wheelIndex = requestBody.wheelIndex ?? 0
            let timeoutSeconds = TimeInterval(requestBody.waitToSettleTimeoutMs ?? Self.defaultPickerExistenceTimeoutMs) / 1000.0
            let wheel = app.pickerWheels.element(boundBy: wheelIndex)

            guard wheel.waitForExistence(timeout: timeoutSeconds) else {
                let wheelCount = app.pickerWheels.count
                return AppError(
                    type: .precondition,
                    message: "No picker wheel at index \(wheelIndex) on foreground app (found \(wheelCount) wheel(s)). " +
                        "Did the picker open? Try increasing waitToSettleTimeoutMs (current: \(Int(timeoutSeconds * 1000))ms)."
                ).httpResponse
            }

            wheel.adjust(toPickerWheelValue: requestBody.value)

            // Verify the wheel actually landed on the target value. XCTest's adjust(toPickerWheelValue:)
            // does not throw on miss in all SDK versions — confirm explicitly.
            let landedValue = wheel.value as? String
            if landedValue != requestBody.value {
                return AppError(
                    type: .precondition,
                    message: "setPickerValue did not land on '\(requestBody.value)' " +
                        "(wheel value is '\(landedValue ?? "<nil>")'). " +
                        "Check that the value matches a picker label exactly (case-sensitive, no extra whitespace)."
                ).httpResponse
            }

            let duration = Date().timeIntervalSince(start)
            logger.info("setPickerValue('\(requestBody.value)', wheelIndex: \(wheelIndex)) took \(duration)s")
            return HTTPResponse(statusCode: .ok)
        } catch {
            return AppError(message: "Error setting picker value: \(error.localizedDescription)").httpResponse
        }
    }
}
