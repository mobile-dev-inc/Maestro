//
//  Created by Helge Heß on 2019-01-30.
//  Copyright © 2019 ZeeZide GmbH. All rights reserved.
//

import Foundation

@dynamicMemberLookup
public struct ObjCRuntime {

    public typealias Args = KeyValuePairs<String, Any>

    @dynamicCallable
    public struct Callable { // `object.doIt`

        let instance : Object
        let baseName : String

        @discardableResult
        public func dynamicallyCall(withKeywordArguments arguments: Args)
        -> Object
        {
            guard let target = instance.handle else { return instance }

            let stringSelector = arguments.reduce(baseName) {
                $0 + $1.key + ":"
            }
            let selector = sel_getUid(stringSelector)

            guard let isa = object_getClass(target),
                  let i = class_getInstanceMethod(isa, selector) else {
                return Object(handle: nil)
            }
            let m = method_getImplementation(i)

            var buf = [ Int8 ](repeating: 0, count: 46)
            method_getReturnType(i, &buf, buf.count)
            let returnType = String(cString: &buf)

            typealias M0 = @convention(c)
            ( AnyObject?, Selector ) -> UnsafeRawPointer?
            typealias M1 = @convention(c)
            ( AnyObject?, Selector, AnyObject? ) -> UnsafeRawPointer?

            let result : UnsafeRawPointer?
            switch arguments.count {
            case 0:
                let typedMethod = unsafeBitCast(m, to: M0.self)
                result = typedMethod(target, selector)
            case 1:
                let typedMethod = unsafeBitCast(m, to: M1.self)
                result = typedMethod(target, selector,
                                     arguments[0].value as AnyObject)
            default:
                fatalError("can't do that count yet!")
            }

            if returnType == "@" {
                guard let result = result else {
                    return Object(handle: nil)
                }

                let p = Unmanaged<AnyObject>.fromOpaque(result)
                return shouldReleaseResult(of: stringSelector)
                ? Object(handle: p.takeRetainedValue())
                : Object(handle: p.takeUnretainedValue())
            }
            return self.instance
        }

        private func shouldReleaseResult(of selector: String) -> Bool {
            return selector.starts(with: "alloc")
            || selector.starts(with: "init")
            || selector.starts(with: "new")
            || selector.starts(with: "copy")
        }
    }

    @dynamicMemberLookup
    public struct Object {

        let handle : AnyObject?

        public subscript(dynamicMember key: String) -> Callable {
            return Callable(instance: self, baseName: key)
        }
    }

    @dynamicCallable
    @dynamicMemberLookup
    public struct Class {
        let handle : AnyClass?

        public subscript(dynamicMember key: String) -> Callable {
            return Callable(instance: Object(handle: self.handle),
                            baseName: key)
        }

        @discardableResult
        public func dynamicallyCall(withKeywordArguments args: Args)
        -> Object
        {
            return self
                .alloc()
                .`init`
                .dynamicallyCall(withKeywordArguments: args)
        }
    }

    public subscript(dynamicMember key: String) -> Class {
        return Class(handle: objc_lookUpClass(key))
    }
}

public let ObjC = ObjCRuntime()
