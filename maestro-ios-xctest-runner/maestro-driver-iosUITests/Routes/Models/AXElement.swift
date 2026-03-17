
import Foundation
import XCTest
import MaestroDriverLib

struct ViewHierarchy : Codable {
    let axElement: AXElement
    let depth: Int
}

struct WindowOffset: Codable {
    let offsetX: Double
    let offsetY: Double
}

// MARK: - XCTest-specific AXElement Extension

extension AXElement {
    init(_ dict: [XCUIElement.AttributeName: Any]) {
        func valueFor(_ name: String) -> Any {
            dict[XCUIElement.AttributeName(rawValue: name)] as Any
        }

        let label = valueFor("label") as? String ?? ""
        let elementType = valueFor("elementType") as? Int ?? 0
        let identifier = valueFor("identifier") as? String ?? ""
        let horizontalSizeClass = valueFor("horizontalSizeClass") as? Int ?? 0
        let windowContextID = valueFor("windowContextID") as? Double ?? 0
        let verticalSizeClass = valueFor("verticalSizeClass") as? Int ?? 0
        let selected = valueFor("selected") as? Bool ?? false
        let displayID = valueFor("displayID") as? Int ?? 0
        let hasFocus = valueFor("hasFocus") as? Bool ?? false
        let placeholderValue = valueFor("placeholderValue") as? String
        let value = valueFor("value") as? String
        let frame = valueFor("frame") as? AXFrame ?? .zero
        let enabled = valueFor("enabled") as? Bool ?? false
        let title = valueFor("title") as? String
        let childrenDictionaries = valueFor("children") as? [[XCUIElement.AttributeName: Any]]
        let children = childrenDictionaries?.map { AXElement($0) } ?? []

        self.init(
            identifier: identifier,
            frame: frame,
            value: value,
            title: title,
            label: label,
            elementType: elementType,
            enabled: enabled,
            horizontalSizeClass: horizontalSizeClass,
            verticalSizeClass: verticalSizeClass,
            placeholderValue: placeholderValue,
            selected: selected,
            hasFocus: hasFocus,
            displayID: displayID,
            windowContextID: windowContextID,
            children: children
        )
    }
}