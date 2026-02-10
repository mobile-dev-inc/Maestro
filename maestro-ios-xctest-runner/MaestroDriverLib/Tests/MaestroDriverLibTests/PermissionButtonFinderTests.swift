import XCTest
@testable import MaestroDriverLib

final class PermissionButtonFinderTests: XCTestCase {

    var sut: PermissionButtonFinder!

    override func setUp() {
        super.setUp()
        sut = PermissionButtonFinder()
    }

    override func tearDown() {
        sut = nil
        super.tearDown()
    }

    // MARK: - Helper

    /// Creates a hierarchy that looks like a real notification permission dialog:
    /// a root element containing a label with the notification text, plus buttons.
    private func makeNotificationPermissionDialog(buttons: [AXElement]) -> AXElement {
        let notificationLabel = AXElement(
            label: "\u{201C}MyApp\u{201D} Would Like to Send You Notifications",
            elementType: 0, // Static text
            children: nil
        )
        return AXElement(
            elementType: 0,
            children: [notificationLabel] + buttons
        )
    }

    // MARK: - findButtons Tests

    func testFindButtons_withNoButtons_returnsEmptyArray() {
        let hierarchy = AXElement(
            elementType: 0, // Not a button
            children: []
        )

        let buttons = sut.findButtons(in: hierarchy)

        XCTAssertTrue(buttons.isEmpty)
    }

    func testFindButtons_withSingleButton_returnsButton() {
        let button = AXElement(
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )

        let buttons = sut.findButtons(in: button)

        XCTAssertEqual(buttons.count, 1)
        XCTAssertEqual(buttons.first?.label, "Allow")
    }

    func testFindButtons_withNestedButtons_returnsAllButtons() {
        let allowButton = AXElement(
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let denyButton = AXElement(
            label: "Don't Allow",
            elementType: ElementType.button,
            children: nil
        )
        let container = AXElement(
            elementType: 0, // Container, not a button
            children: [allowButton, denyButton]
        )
        let hierarchy = AXElement(
            elementType: 0,
            children: [container]
        )

        let buttons = sut.findButtons(in: hierarchy)

        XCTAssertEqual(buttons.count, 2)
        XCTAssertEqual(buttons[0].label, "Allow")
        XCTAssertEqual(buttons[1].label, "Don't Allow")
    }

    func testFindButtons_withDeeplyNestedButtons_findsAllButtons() {
        let deepButton = AXElement(
            label: "Deep Button",
            elementType: ElementType.button,
            children: nil
        )
        let level2 = AXElement(
            elementType: 0,
            children: [deepButton]
        )
        let level1 = AXElement(
            elementType: 0,
            children: [level2]
        )
        let hierarchy = AXElement(
            elementType: 0,
            children: [level1]
        )

        let buttons = sut.findButtons(in: hierarchy)

        XCTAssertEqual(buttons.count, 1)
        XCTAssertEqual(buttons.first?.label, "Deep Button")
    }

    // MARK: - isPermissionDialog Tests

    func testIsPermissionDialog_withNotificationLabel_returnsTrue() {
        let label = AXElement(
            label: "\u{201C}MyApp\u{201D} Would Like to Send You Notifications",
            elementType: 0,
            children: nil
        )
        let hierarchy = AXElement(children: [label])

        XCTAssertTrue(sut.isPermissionDialog(hierarchy))
    }

    func testIsPermissionDialog_withNestedNotificationLabel_returnsTrue() {
        let label = AXElement(
            label: "App Would Like to Send You Notifications",
            elementType: 0,
            children: nil
        )
        let nested = AXElement(elementType: 0, children: [label])
        let hierarchy = AXElement(children: [nested])

        XCTAssertTrue(sut.isPermissionDialog(hierarchy))
    }

    func testIsPermissionDialog_isCaseInsensitive() {
        let label = AXElement(
            label: "app would like to send you notifications",
            elementType: 0,
            children: nil
        )
        let hierarchy = AXElement(children: [label])

        XCTAssertTrue(sut.isPermissionDialog(hierarchy))
    }

    func testIsPermissionDialog_withOpenInDialog_returnsFalse() {
        let label = AXElement(
            label: "Open in \u{201C}My App Staging\u{201D}",
            elementType: 0,
            children: nil
        )
        let hierarchy = AXElement(children: [label])

        XCTAssertFalse(sut.isPermissionDialog(hierarchy))
    }

    func testIsPermissionDialog_withUnrelatedDialog_returnsFalse() {
        let label = AXElement(
            label: "Are you sure you want to delete this?",
            elementType: 0,
            children: nil
        )
        let hierarchy = AXElement(children: [label])

        XCTAssertFalse(sut.isPermissionDialog(hierarchy))
    }

    func testIsPermissionDialog_withEmptyHierarchy_returnsFalse() {
        let hierarchy = AXElement(children: [])

        XCTAssertFalse(sut.isPermissionDialog(hierarchy))
    }

    // MARK: - findButtonToTap Tests (Allow Permission)

    func testFindButtonToTap_allowPermission_findsAllowButtonByLabel() {
        let allowButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let denyButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Don't Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [denyButton, allowButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 100)
            XCTAssertEqual(frame["Y"], 200)
        } else {
            XCTFail("Expected .found result")
        }
    }

    func testFindButtonToTap_allowPermission_findsContinueButton() {
        let continueButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Continue",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [continueButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 100)
        } else {
            XCTFail("Expected .found result")
        }
    }

    func testFindButtonToTap_allowPermission_fallsBackToSecondButton() {
        let firstButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Nope",
            elementType: ElementType.button,
            children: nil
        )
        let secondButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "OK",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [firstButton, secondButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 100)
        } else {
            XCTFail("Expected .found result")
        }
    }

    // MARK: - findButtonToTap Tests (Deny Permission)

    func testFindButtonToTap_denyPermission_findsDontAllowButtonByLabel() {
        let allowButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let denyButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Don't Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [allowButton, denyButton])

        let result = sut.findButtonToTap(for: .deny, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 10)
        } else {
            XCTFail("Expected .found result")
        }
    }

    func testFindButtonToTap_denyPermission_findsCancelButton() {
        let cancelButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Cancel",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [cancelButton])

        let result = sut.findButtonToTap(for: .deny, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 10)
        } else {
            XCTFail("Expected .found result")
        }
    }

    func testFindButtonToTap_denyPermission_fallsBackToFirstButton() {
        let firstButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Nope",
            elementType: ElementType.button,
            children: nil
        )
        let secondButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "OK",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [firstButton, secondButton])

        let result = sut.findButtonToTap(for: .deny, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 10)
        } else {
            XCTFail("Expected .found result")
        }
    }

    // MARK: - findButtonToTap Tests (No Action Required)

    func testFindButtonToTap_unsetPermission_returnsNoActionRequired() {
        let hierarchy = AXElement(children: [])

        let result = sut.findButtonToTap(for: .unset, in: hierarchy)

        XCTAssertEqual(result, .noActionRequired)
    }

    func testFindButtonToTap_unknownPermission_returnsNoActionRequired() {
        let hierarchy = AXElement(children: [])

        let result = sut.findButtonToTap(for: .unknown, in: hierarchy)

        XCTAssertEqual(result, .noActionRequired)
    }

    // MARK: - findButtonToTap Tests (No Buttons Found)

    func testFindButtonToTap_noButtonsInHierarchy_returnsNoButtonsFound() {
        let notificationLabel = AXElement(
            label: "App Would Like to Send You Notifications",
            elementType: 0,
            children: nil
        )
        let textElement = AXElement(
            elementType: 0,
            children: nil
        )
        let hierarchy = AXElement(children: [notificationLabel, textElement])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        XCTAssertEqual(result, .noButtonsFound)
    }

    // MARK: - findButtonToTap Tests (Not a Permission Dialog)

    func testFindButtonToTap_openInDialog_returnsNotPermissionDialog() {
        let label = AXElement(
            label: "Open in \u{201C}My App Staging\u{201D}",
            elementType: 0,
            children: nil
        )
        let openButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = AXElement(children: [label, openButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        XCTAssertEqual(result, .noActionRequired)
    }

    func testFindButtonToTap_openInDialog_doesNotTapDenyButton() {
        let label = AXElement(
            label: "Open in \u{201C}My App Staging\u{201D}",
            elementType: 0,
            children: nil
        )
        let cancelButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Cancel",
            elementType: ElementType.button,
            children: nil
        )
        let allowButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = AXElement(children: [label, cancelButton, allowButton])

        let result = sut.findButtonToTap(for: .deny, in: hierarchy)

        XCTAssertEqual(result, .noActionRequired)
    }

    func testFindButtonToTap_genericDeleteDialog_returnsNotPermissionDialog() {
        let label = AXElement(
            label: "Are you sure you want to delete this?",
            elementType: 0,
            children: nil
        )
        let deleteButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Delete",
            elementType: ElementType.button,
            children: nil
        )
        let cancelButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "Cancel",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = AXElement(children: [label, deleteButton, cancelButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        XCTAssertEqual(result, .noActionRequired)
    }

    func testFindButtonToTap_dialogWithNoLabels_returnsNotPermissionDialog() {
        let button = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = AXElement(children: [button])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        XCTAssertEqual(result, .noActionRequired)
    }

    // MARK: - findButtonToTap with notification dialog selects correct button

    func testFindButtonToTap_notificationDialog_allowSelectsCorrectButton() {
        let allowButton = AXElement(
            frame: ["X": 200, "Y": 300, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let denyButton = AXElement(
            frame: ["X": 50, "Y": 300, "Width": 80, "Height": 40],
            label: "Don't Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [denyButton, allowButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 200, "Should select the Allow button, not Don't Allow")
        } else {
            XCTFail("Expected .found result for notification permission dialog")
        }
    }

    func testFindButtonToTap_notificationDialog_denySelectsCorrectButton() {
        let allowButton = AXElement(
            frame: ["X": 200, "Y": 300, "Width": 80, "Height": 40],
            label: "Allow",
            elementType: ElementType.button,
            children: nil
        )
        let denyButton = AXElement(
            frame: ["X": 50, "Y": 300, "Width": 80, "Height": 40],
            label: "Don't Allow",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [denyButton, allowButton])

        let result = sut.findButtonToTap(for: .deny, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 50, "Should select the Don't Allow button")
        } else {
            XCTFail("Expected .found result for notification permission dialog")
        }
    }

    // MARK: - Case Insensitivity Tests

    func testFindButtonToTap_allowPermission_isCaseInsensitive() {
        let allowButton = AXElement(
            frame: ["X": 100, "Y": 200, "Width": 80, "Height": 40],
            label: "ALLOW",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [allowButton])

        let result = sut.findButtonToTap(for: .allow, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 100)
        } else {
            XCTFail("Expected .found result")
        }
    }

    func testFindButtonToTap_denyPermission_isCaseInsensitive() {
        let denyButton = AXElement(
            frame: ["X": 10, "Y": 200, "Width": 80, "Height": 40],
            label: "DON'T ALLOW",
            elementType: ElementType.button,
            children: nil
        )
        let hierarchy = makeNotificationPermissionDialog(buttons: [denyButton])

        let result = sut.findButtonToTap(for: .deny, in: hierarchy)

        if case .found(let frame) = result {
            XCTAssertEqual(frame["X"], 10)
        } else {
            XCTFail("Expected .found result")
        }
    }
}
