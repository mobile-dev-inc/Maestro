
import Foundation
import XCTest

struct ViewHierarchy : Codable {
    let axElement: AXElement
    let depth: Int
}

struct WindowOffset: Codable {
    let offsetX: Double
    let offsetY: Double
}

typealias AXFrame = [String: Double]
extension AXFrame {
    static var zero: Self {
        ["X": 0, "Y": 0, "Width": 0, "Height": 0]
    }
}

struct AXElement: Codable {
    let identifier: String
    let frame: AXFrame
    let value: String?
    let title: String?
    let label: String
    let elementType: Int
    let enabled: Bool
    let horizontalSizeClass: Int
    let verticalSizeClass: Int
    let placeholderValue: String?
    let selected: Bool
    let hasFocus: Bool
    var children: [AXElement]?
    let windowContextID: Double
    let displayID: Int
    
    init(children: [AXElement]) {
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
    
    init(
        identifier: String, frame: AXFrame, value: String?, title: String?, label: String,
        elementType: Int, enabled: Bool, horizontalSizeClass: Int,
        verticalSizeClass: Int, placeholderValue: String?, selected: Bool,
        hasFocus: Bool, displayID: Int, windowContextID: Double, children: [AXElement]
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
    
    init(_ dict: [XCUIElement.AttributeName: Any]) {
        func valueFor(_ name: String) -> Any {
            dict[XCUIElement.AttributeName(rawValue: name)] as Any
        }
        
        self.label = valueFor("label") as? String ?? ""
        self.elementType = valueFor("elementType") as? Int ?? 0
        self.identifier = valueFor("identifier") as? String ?? ""
        self.horizontalSizeClass = valueFor("horizontalSizeClass") as? Int ?? 0
        self.windowContextID = valueFor("windowContextID") as? Double ?? 0
        self.verticalSizeClass = valueFor("verticalSizeClass") as? Int ?? 0
        self.selected = valueFor("selected") as? Bool ?? false
        self.displayID = valueFor("displayID") as? Int ?? 0
        self.hasFocus = valueFor("hasFocus") as? Bool ?? false
        self.placeholderValue = valueFor("placeholderValue") as? String
        self.value = valueFor("value") as? String
        self.frame = valueFor("frame") as? AXFrame ?? .zero
        self.enabled = valueFor("enabled") as? Bool ?? false
        self.title = valueFor("title") as? String
        let childrenDictionaries = valueFor("children") as? [[XCUIElement.AttributeName: Any]]
        self.children = childrenDictionaries?.map { AXElement($0) } ?? []
    }
    
    func encode(to encoder: Encoder) throws {
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
    
    func depth() -> Int {
        guard let children = children
        else { return 1 }
        
        let max = children
            .map { child in child.depth() + 1 }
            .max()
        
        return max ?? 1
    }
    
    
    func filterAllChildrenNotInKeyboardBounds(_ keyboardFrame: CGRect) -> [AXElement] {
        var filteredChildren = [AXElement]()
        
        // Function to recursively filter children
        func filterChildrenRecursively(_ element: AXElement, _ ancestorAdded: Bool) {
            // Check if the element's frame intersects with the keyboard frame
            let childFrame = CGRect(
                x: element.frame["X"] ?? 0,
                y: element.frame["Y"] ?? 0,
                width: element.frame["Width"] ?? 0,
                height: element.frame["Height"] ?? 0
            )
            
            var currentAncestorAdded = ancestorAdded
            
            // If it does not intersect, and no ancestor has been added, append the element
            if !keyboardFrame.intersects(childFrame) && !ancestorAdded {
                filteredChildren.append(element)
                currentAncestorAdded = true // Prevent adding descendants of this element
            }
            
            // Continue recursion with children
            element.children?.forEach { child in
                filterChildrenRecursively(child, currentAncestorAdded)
            }
        }
        
        // Start the recursive filtering with no ancestor added
        filterChildrenRecursively(self, false)
        return filteredChildren
    }
}
