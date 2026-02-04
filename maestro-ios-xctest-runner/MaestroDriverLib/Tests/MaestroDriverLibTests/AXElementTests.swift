import XCTest
@testable import MaestroDriverLib

final class AXElementTests: XCTestCase {

    // MARK: - Initialization Tests

    func testInit_withChildren_setsDefaultValues() {
        let child = AXElement(label: "Child", elementType: 1, children: nil)
        let element = AXElement(children: [child])

        XCTAssertEqual(element.label, "")
        XCTAssertEqual(element.elementType, 0)
        XCTAssertEqual(element.identifier, "")
        XCTAssertEqual(element.children?.count, 1)
        XCTAssertEqual(element.frame, .zero)
    }

    func testInit_withAllParameters_setsAllValues() {
        let frame: AXFrame = ["X": 10, "Y": 20, "Width": 100, "Height": 50]
        let element = AXElement(
            identifier: "test-id",
            frame: frame,
            value: "test-value",
            title: "Test Title",
            label: "Test Label",
            elementType: 9,
            enabled: true,
            horizontalSizeClass: 1,
            verticalSizeClass: 2,
            placeholderValue: "placeholder",
            selected: true,
            hasFocus: true,
            displayID: 1,
            windowContextID: 123.0,
            children: nil
        )

        XCTAssertEqual(element.identifier, "test-id")
        XCTAssertEqual(element.frame["X"], 10)
        XCTAssertEqual(element.value, "test-value")
        XCTAssertEqual(element.title, "Test Title")
        XCTAssertEqual(element.label, "Test Label")
        XCTAssertEqual(element.elementType, 9)
        XCTAssertTrue(element.enabled)
        XCTAssertEqual(element.horizontalSizeClass, 1)
        XCTAssertEqual(element.verticalSizeClass, 2)
        XCTAssertEqual(element.placeholderValue, "placeholder")
        XCTAssertTrue(element.selected)
        XCTAssertTrue(element.hasFocus)
        XCTAssertEqual(element.displayID, 1)
        XCTAssertEqual(element.windowContextID, 123.0)
        XCTAssertNil(element.children)
    }

    // MARK: - Depth Tests

    func testDepth_withNoChildren_returnsOne() {
        let element = AXElement(
            elementType: 0,
            children: nil
        )

        XCTAssertEqual(element.depth(), 1)
    }

    func testDepth_withEmptyChildren_returnsOne() {
        let element = AXElement(children: [])

        XCTAssertEqual(element.depth(), 1)
    }

    func testDepth_withOneLevel_returnsTwo() {
        let child = AXElement(elementType: 0, children: nil)
        let element = AXElement(children: [child])

        XCTAssertEqual(element.depth(), 2)
    }

    func testDepth_withMultipleLevels_returnsCorrectDepth() {
        let grandchild = AXElement(elementType: 0, children: nil)
        let child = AXElement(elementType: 0, children: [grandchild])
        let element = AXElement(children: [child])

        XCTAssertEqual(element.depth(), 3)
    }

    func testDepth_withUnevenTree_returnsMaxDepth() {
        // Create a tree where one branch is deeper than the other
        let deepGrandchild = AXElement(elementType: 0, children: nil)
        let deepChild = AXElement(elementType: 0, children: [deepGrandchild])
        let shallowChild = AXElement(elementType: 0, children: nil)
        let element = AXElement(children: [deepChild, shallowChild])

        XCTAssertEqual(element.depth(), 3)
    }

    // MARK: - Codable Tests

    func testCodable_encodesAndDecodesCorrectly() throws {
        let original = AXElement(
            identifier: "test",
            frame: ["X": 10, "Y": 20, "Width": 100, "Height": 50],
            value: "value",
            title: "title",
            label: "label",
            elementType: 9,
            enabled: true,
            horizontalSizeClass: 1,
            verticalSizeClass: 2,
            placeholderValue: nil,
            selected: false,
            hasFocus: true,
            displayID: 1,
            windowContextID: 100,
            children: nil
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(original)

        let decoder = JSONDecoder()
        let decoded = try decoder.decode(AXElement.self, from: data)

        XCTAssertEqual(decoded.identifier, original.identifier)
        XCTAssertEqual(decoded.label, original.label)
        XCTAssertEqual(decoded.elementType, original.elementType)
        XCTAssertEqual(decoded.frame["X"], original.frame["X"])
    }

    // MARK: - Equatable Tests

    func testEquatable_equalElements_areEqual() {
        let element1 = AXElement(
            identifier: "test",
            label: "Test",
            elementType: 9,
            children: nil
        )
        let element2 = AXElement(
            identifier: "test",
            label: "Test",
            elementType: 9,
            children: nil
        )

        XCTAssertEqual(element1, element2)
    }

    func testEquatable_differentElements_areNotEqual() {
        let element1 = AXElement(
            identifier: "test1",
            label: "Test 1",
            elementType: 9,
            children: nil
        )
        let element2 = AXElement(
            identifier: "test2",
            label: "Test 2",
            elementType: 9,
            children: nil
        )

        XCTAssertNotEqual(element1, element2)
    }
}