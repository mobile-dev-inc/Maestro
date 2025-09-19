import Foundation
import XCTest

extension XCUIElement {
    func setText(text: String, application: XCUIApplication) {
        // TODO: Does this require a tvOS implementation?
        #if !os(tvOS)
        UIPasteboard.general.string = text
        doubleTap()
        application.menuItems["Paste"].tap()
        #endif
    }
}
