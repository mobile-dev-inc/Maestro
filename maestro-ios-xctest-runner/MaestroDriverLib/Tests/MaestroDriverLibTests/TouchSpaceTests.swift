import XCTest
@testable import MaestroDriverLib

/// Points and sizes are from an iPhone Duo simulator (iOS 27.1): the centre of a button as the view
/// hierarchy reports it, and where a synthesized touch had to go for that button to receive it.
final class TouchSpaceTests: XCTestCase {

    private let outer = CGSize(width: 466, height: 678)
    private let inner = CGSize(width: 669, height: 951)

    func testPortrait_leavesPointUnchanged() {
        let point = TouchSpace.touchPoint(CGPoint(x: 65, y: 123), orientation: .portrait, portraitSize: inner)

        XCTAssertEqual(point, CGPoint(x: 65, y: 123))
    }

    func testPortraitUpsideDown_mirrorsBothAxes() {
        let point = TouchSpace.touchPoint(CGPoint(x: 65, y: 123), orientation: .portraitUpsideDown, portraitSize: inner)

        XCTAssertEqual(point, CGPoint(x: 604, y: 828))
    }

    func testLandscapeRight_turnsPointClockwise() {
        let point = TouchSpace.touchPoint(CGPoint(x: 149, y: 41), orientation: .landscapeRight, portraitSize: outer)

        XCTAssertEqual(point, CGPoint(x: 425, y: 149))
    }

    func testLandscapeLeft_turnsPointCounterClockwise() {
        let point = TouchSpace.touchPoint(CGPoint(x: 65, y: 40), orientation: .landscapeLeft, portraitSize: inner)

        XCTAssertEqual(point, CGPoint(x: 40, y: 886))
    }

    func testLandscapeLeft_mapsOppositeCornerInsideTheScreen() {
        // Bottom-right of the upright 951×669 landscape UI.
        let point = TouchSpace.touchPoint(CGPoint(x: 782, y: 593), orientation: .landscapeLeft, portraitSize: inner)

        XCTAssertEqual(point, CGPoint(x: 593, y: 169))
    }

    func testInterfaceOrientation_sharesUIKitRawValues() {
        XCTAssertEqual(InterfaceOrientation(rawValue: 1), .portrait)
        XCTAssertEqual(InterfaceOrientation(rawValue: 2), .portraitUpsideDown)
        XCTAssertEqual(InterfaceOrientation(rawValue: 3), .landscapeRight)
        XCTAssertEqual(InterfaceOrientation(rawValue: 4), .landscapeLeft)
        XCTAssertNil(InterfaceOrientation(rawValue: 0))
    }

    func testUprightSize_swapsForLandscape() {
        XCTAssertEqual(TouchSpace.uprightSize(portraitSize: inner, orientation: .landscapeLeft), CGSize(width: 951, height: 669))
        XCTAssertEqual(TouchSpace.uprightSize(portraitSize: inner, orientation: .portraitUpsideDown), inner)
    }
}
