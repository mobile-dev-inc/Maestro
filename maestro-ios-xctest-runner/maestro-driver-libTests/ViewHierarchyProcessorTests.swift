import XCTest
@testable import maestro_driver_lib

final class ViewHierarchyProcessorTests: XCTestCase {

    // Helpers
    private func ax(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, kids: [AXElement]? = nil) -> AXElement {
        let f: AXFrame = ["X": Double(x), "Y": Double(y), "Width": Double(w), "Height": Double(h)]
        return AXElement(
            identifier: "id", frame: f, value: nil, title: nil, label: "",
            elementType: 0, enabled: true, horizontalSizeClass: 0, verticalSizeClass: 0,
            placeholderValue: nil, selected: false, hasFocus: false, displayID: 0, windowContextID: 0,
            children: kids
        )
    }

    // Portrait → no change
    func testPortrait_NoTransform() {
        let screen = ScreenContext(deviceOrientation: .portrait, deviceWidth: 390, deviceHeight: 844)
        let p = ViewHierarchyProcessor(screenContext: screen)

        let root = ax(10, 20, 100, 50)
        let out = p.process(root) // auto-decide: false for portrait

        XCTAssertEqual(out.frame["X"], 10)
        XCTAssertEqual(out.frame["Y"], 20)
        XCTAssertEqual(out.frame["Width"], 100)
        XCTAssertEqual(out.frame["Height"], 50)
    }

    // LandscapeLeft rotation
    func testLandscapeLeft_Rotate() {
        // given
        let screen = ScreenContext(deviceOrientation: .landscapeLeft, deviceWidth: 1024, deviceHeight: 768)
        let p = ViewHierarchyProcessor(screenContext: screen)
        
        // Portrait-space rect (l=10,t=20,w=100,h=50) => r=110,b=70
        let root = ax(0, 0, 768, 1024)
        let out  = p.process(root) // auto-detect
        
        // then
        XCTAssertEqual(out.frame["X"], 0)
        XCTAssertEqual(out.frame["Y"], 0)
        XCTAssertEqual(out.frame["Width"], 1024)
        XCTAssertEqual(out.frame["Height"], 768)
    }

    func testLandscapeRight_Rotate_AutoDetect_WithChildren() {
        // given
        // Device is landscape 1024×768
        let screen = ScreenContext(deviceOrientation: .landscapeRight, deviceWidth: 1024, deviceHeight: 768)
        let p = ViewHierarchyProcessor(screenContext: screen)

        // Child rects in PORTRAIT space:
        // child1: l=10,t=20,w=100,h=50 => r=110,b=70  → LR: (x=1024-70=954, y=l=10, w=50, h=100)
        // child2: l=200,t=30,w=40,h=60  => r=240,b=90  → LR: (x=1024-90=934,  y=200, w=60, h=40)
        let child1 = ax(10, 20, 100, 50)
        let child2 = ax(200, 30, 40, 60)

        // ROOT must look like a portrait app window so auto-detect rotates:
        let root = ax(0, 0, 768, 1024, kids: [child1, child2])

        
        // when
        let out = p.process(root)

        
        // then
        
        // Root becomes landscape app window
        XCTAssertEqual(out.frame["X"], 0)
        XCTAssertEqual(out.frame["Y"], 0)
        XCTAssertEqual(out.frame["Width"], 1024)
        XCTAssertEqual(out.frame["Height"], 768)

        // --- child1 ---
        guard let c1 = out.children?.first else { return XCTFail("missing child1") }
        XCTAssertEqual(c1.frame["X"], 954)   // W - b = 1024 - 70
        XCTAssertEqual(c1.frame["Y"], 10)    // l
        XCTAssertEqual(c1.frame["Width"], 50)  // b - t = 70 - 20
        XCTAssertEqual(c1.frame["Height"], 100) // r - l = 110 - 10

        // --- child2 ---
        guard out.children?.count == 2, let c2 = out.children?[1] else { return XCTFail("missing child2") }
        XCTAssertEqual(c2.frame["X"], 934)   // W - b = 1024 - 90
        XCTAssertEqual(c2.frame["Y"], 200)   // l
        XCTAssertEqual(c2.frame["Width"], 60)  // b - t = 90 - 30
        XCTAssertEqual(c2.frame["Height"], 40) // r - l = 240 - 200
    }

    // UpsideDown rotation (180°)
    func testPortraitUpsideDown_Rotate() {
        let screen = ScreenContext(deviceOrientation: .portraitUpsideDown, deviceWidth: 390, deviceHeight: 844)
        let p = ViewHierarchyProcessor(screenContext: screen)

        let root = ax(10, 20, 100, 50) // r=110,b=70
        let out = p.process(root)

        // Expected: x=W-r=390-110=280, y=H-b=844-70=774, w=100, h=50

        XCTAssertEqual(out.frame["X"], 280)
        XCTAssertEqual(out.frame["Y"], 774)
        XCTAssertEqual(out.frame["Width"], 100)
        XCTAssertEqual(out.frame["Height"], 50)
    }
}
