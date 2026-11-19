import XCTest
@testable import MaestroDriverLib

final class HostedHierarchyTests: XCTestCase {

    private let picker = AXElement(children: [
        AXElement(label: "Photos", children: [
            AXElement(identifier: "PXGGridLayout-Info", label: "Photo, 1"),
            AXElement(identifier: "PXGGridLayout-Info", label: "Photo, 2"),
            AXElement(label: "Cancel"),
        ]),
    ])

    func testAppWithoutPickerContent_isNotIncluded() {
        let app = AXElement(children: [
            AXElement(label: "MyApp", children: [AXElement()]),
        ])
        XCTAssertFalse(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testAppSharingOnlyACommonLabel_isNotIncluded() {
        let app = AXElement(children: [AXElement(label: "Cancel")])
        XCTAssertFalse(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testPickerFoldedIntoApp_isIncluded() {
        let app = AXElement(children: [
            AXElement(label: "MyApp"),
            picker,
        ])
        XCTAssertTrue(HostedHierarchy.isIncluded(picker, in: app))
    }

    func testHostedTreeWithoutContent_isNotIncluded() {
        let hosted = AXElement(children: [AXElement()])
        XCTAssertFalse(HostedHierarchy.isIncluded(hosted, in: AXElement(children: [AXElement()])))
    }
}
