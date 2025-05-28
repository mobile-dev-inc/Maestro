import Foundation
import XCTest

// Use a global to pass to the swizzled implementation:
// Instance variables are not accessible after swizzling.
private var _overwriteDefaultParameters = [String: Int]()

struct AXClientSwizzler {

    // Make this type not-initializable
    private init() {}

    static var overwriteDefaultParameters: [String: Int] {
        get { _overwriteDefaultParameters }
        set { setup; _overwriteDefaultParameters = newValue }
    }

    static let setup: Void = {
        let proxy = AXClientiOS_Standin()
        let axClientiOSClass: AnyClass = objc_getClass("XCAXClient_iOS") as! AnyClass
        let defaultParametersSelector = Selector(("defaultParameters"))
        let original = class_getInstanceMethod(axClientiOSClass, defaultParametersSelector)!

        let replaced = class_getInstanceMethod(
            AXClientiOS_Standin.self,
            #selector(AXClientiOS_Standin.swizzledDefaultParameters))!
        
        method_exchangeImplementations(original, replaced)
    }()
}

@objc private class AXClientiOS_Standin: NSObject {
    func originalDefaultParameters() -> NSDictionary {
        let selector = Selector(("defaultParameters"))
        let swizzeledSelector = #selector(swizzledDefaultParameters)
        let imp = class_getMethodImplementation(AXClientiOS_Standin.self, swizzeledSelector)
        typealias Method = @convention(c) (NSObject, Selector) -> NSDictionary
        let method = unsafeBitCast(imp, to: Method.self)
        return method(self, selector)
    }

    @objc func swizzledDefaultParameters() -> NSDictionary {
        let defaultParameters = originalDefaultParameters().mutableCopy() as! NSMutableDictionary
        
        // Log parameters before modification
        NSLog("Default parameters before modification: %@", defaultParameters)

        for (key, value) in _overwriteDefaultParameters {
            defaultParameters[key] = value
        }
        
        // Log parameters after modification
        NSLog("Default parameters after modification: %@", defaultParameters)

        return defaultParameters
    }
}
