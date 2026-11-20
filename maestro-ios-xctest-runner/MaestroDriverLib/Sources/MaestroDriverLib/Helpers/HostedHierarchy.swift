import Foundation

/// UI that runs in another process and is hosted inside the app, such as the
/// iOS 26+ photo pickers.
public enum HostedHierarchy {

    /// Whether `app` already contains `hosted`: at least half of the identifiers
    /// and labels in `hosted` also appear in `app`. Window contexts can't tell,
    /// because the app's hosting window shares one with the extension even when
    /// its content is missing.
    public static func isIncluded(_ hosted: AXElement, in app: AXElement) -> Bool {
        let hostedKeys = contentKeys(in: hosted)
        guard !hostedKeys.isEmpty else {
            return false
        }
        let shared = hostedKeys.intersection(contentKeys(in: app))
        return shared.count * 2 >= hostedKeys.count
    }

    private static func contentKeys(in element: AXElement) -> Set<String> {
        var result = Set<String>()
        var stack = [element]
        while let node = stack.popLast() {
            if !node.identifier.isEmpty {
                result.insert("id:" + node.identifier)
            }
            if !node.label.isEmpty {
                result.insert("label:" + node.label)
            }
            stack.append(contentsOf: node.children ?? [])
        }
        return result
    }
}
