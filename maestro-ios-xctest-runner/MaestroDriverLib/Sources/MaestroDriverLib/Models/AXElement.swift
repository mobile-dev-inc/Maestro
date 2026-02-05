import Foundation
import CoreGraphics

/// Represents an accessibility element in the view hierarchy.
/// This is a pure domain model without XCUITest dependencies.
public struct AXElement: Codable, Equatable {
    public let identifier: String
    public let frame: AXFrame
    public let value: String?
    public let title: String?
    public let label: String
    public let elementType: Int
    public let enabled: Bool
    public let horizontalSizeClass: Int
    public let verticalSizeClass: Int
    public let placeholderValue: String?
    public let selected: Bool
    public let hasFocus: Bool
    public var children: [AXElement]?
    public let windowContextID: Double
    public let displayID: Int

    public init(children: [AXElement]) {
        self.children = children
        self.label = ""
        self.elementType = 0
        self.identifier = ""
        self.horizontalSizeClass = 0
        self.windowContextID = 0
        self.verticalSizeClass = 0
        self.selected = false
        self.displayID = 0
        self.hasFocus = false
        self.placeholderValue = nil
        self.value = nil
        self.frame = .zero
        self.enabled = false
        self.title = nil
    }

    public init(
        identifier: String = "",
        frame: AXFrame = .zero,
        value: String? = nil,
        title: String? = nil,
        label: String = "",
        elementType: Int = 0,
        enabled: Bool = false,
        horizontalSizeClass: Int = 0,
        verticalSizeClass: Int = 0,
        placeholderValue: String? = nil,
        selected: Bool = false,
        hasFocus: Bool = false,
        displayID: Int = 0,
        windowContextID: Double = 0,
        children: [AXElement]? = nil
    ) {
        self.identifier = identifier
        self.frame = frame
        self.value = value
        self.title = title
        self.label = label
        self.elementType = elementType
        self.enabled = enabled
        self.horizontalSizeClass = horizontalSizeClass
        self.verticalSizeClass = verticalSizeClass
        self.placeholderValue = placeholderValue
        self.selected = selected
        self.hasFocus = hasFocus
        self.displayID = displayID
        self.windowContextID = windowContextID
        self.children = children
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(self.identifier, forKey: .identifier)
        try container.encode(self.frame, forKey: .frame)
        try container.encodeIfPresent(self.value, forKey: .value)
        try container.encodeIfPresent(self.title, forKey: .title)
        try container.encode(self.label, forKey: .label)
        try container.encode(self.elementType, forKey: .elementType)
        try container.encode(self.enabled, forKey: .enabled)
        try container.encode(self.horizontalSizeClass, forKey: .horizontalSizeClass)
        try container.encode(self.verticalSizeClass, forKey: .verticalSizeClass)
        try container.encodeIfPresent(self.placeholderValue, forKey: .placeholderValue)
        try container.encode(self.selected, forKey: .selected)
        try container.encode(self.hasFocus, forKey: .hasFocus)
        try container.encodeIfPresent(self.children, forKey: .children)
        try container.encode(self.windowContextID, forKey: .windowContextID)
        try container.encode(self.displayID, forKey: .displayID)
    }

    public func depth() -> Int {
        guard let children = children else { return 1 }
        let max = children.map { $0.depth() + 1 }.max()
        return max ?? 1
    }

    public func filterAllChildrenNotInKeyboardBounds(_ keyboardFrame: CGRect) -> [AXElement] {
        var filteredChildren = [AXElement]()

        func filterChildrenRecursively(_ element: AXElement, _ ancestorAdded: Bool) {
            let childFrame = CGRect(
                x: element.frame["X"] ?? 0,
                y: element.frame["Y"] ?? 0,
                width: element.frame["Width"] ?? 0,
                height: element.frame["Height"] ?? 0
            )

            var currentAncestorAdded = ancestorAdded

            if !keyboardFrame.intersects(childFrame) && !ancestorAdded {
                filteredChildren.append(element)
                currentAncestorAdded = true
            }

            element.children?.forEach { child in
                filterChildrenRecursively(child, currentAncestorAdded)
            }
        }

        filterChildrenRecursively(self, false)
        return filteredChildren
    }
}