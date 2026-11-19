import XCTest
@testable import MaestroDriverLib

final class HostedHierarchyTests: XCTestCase {

    /// A picker with 4 content keys: 2 labels and 2 identifiers.
    private let picker = AXElement(children: [
        AXElement(identifier: "photos_layout", label: "Photo, 1"),
        AXElement(identifier: "PXGGridLayout-Info", label: "Cancel"),
    ])

    private func app(_ elements: [AXElement]) -> AXElement {
        AXElement(children: [AXElement(label: "MyApp")] + elements)
    }

    func testAppWithoutPickerContent_isNotIncluded() {
        XCTAssertFalse(HostedHierarchy.isIncluded(picker, in: app([])))
    }

    func testAppWithLessThanHalfOfPickerContent_isNotIncluded() {
        let app = app([AXElement(label: "Cancel")])
        XCTAssertFalse(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testAppWithExactlyHalfOfPickerContent_isIncluded() {
        let app = app([AXElement(label: "Cancel"), AXElement(label: "Photo, 1")])
        XCTAssertTrue(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testMatchesOnIdentifiersAlone_isIncluded() {
        let app = app([AXElement(identifier: "photos_layout"), AXElement(identifier: "PXGGridLayout-Info")])
        XCTAssertTrue(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testLabelInAppMatchingPickerIdentifier_isNotIncluded() {
        let app = app([AXElement(label: "photos_layout"), AXElement(label: "PXGGridLayout-Info")])
        XCTAssertFalse(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testPickerFoldedDeepInsideApp_isIncluded() {
        let app = app([AXElement(children: [AXElement(children: picker.children)])])
        XCTAssertTrue(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testPickerWithoutContent_isNotIncluded() {
        let empty = AXElement(children: [AXElement()])
        XCTAssertFalse(HostedHierarchy.isIncluded(empty, in: app([AXElement()])))
    }
}
