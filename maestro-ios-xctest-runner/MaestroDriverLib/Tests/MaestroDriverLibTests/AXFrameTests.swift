import XCTest
@testable import MaestroDriverLib

final class AXFrameTests: XCTestCase {

    func testZeroFrame_hasAllZeroValues() {
        let frame: AXFrame = .zero

        XCTAssertEqual(frame.x, 0)
        XCTAssertEqual(frame.y, 0)
        XCTAssertEqual(frame.width, 0)
        XCTAssertEqual(frame.height, 0)
    }

    func testFrameAccessors_returnCorrectValues() {
        let frame: AXFrame = [
            "X": 10,
            "Y": 20,
            "Width": 100,
            "Height": 50
        ]

        XCTAssertEqual(frame.x, 10)
        XCTAssertEqual(frame.y, 20)
        XCTAssertEqual(frame.width, 100)
        XCTAssertEqual(frame.height, 50)
    }

    func testCenterX_calculatesCorrectly() {
        let frame: AXFrame = [
            "X": 100,
            "Y": 200,
            "Width": 80,
            "Height": 40
        ]

        XCTAssertEqual(frame.centerX, 140) // 100 + 80/2
    }

    func testCenterY_calculatesCorrectly() {
        let frame: AXFrame = [
            "X": 100,
            "Y": 200,
            "Width": 80,
            "Height": 40
        ]

        XCTAssertEqual(frame.centerY, 220) // 200 + 40/2
    }

    func testFrameWithMissingKeys_usesZeroDefaults() {
        let frame: AXFrame = ["X": 10]

        XCTAssertEqual(frame.x, 10)
        XCTAssertEqual(frame.y, 0)
        XCTAssertEqual(frame.width, 0)
        XCTAssertEqual(frame.height, 0)
    }
}
