import FlyingFox
import XCTest
import os
import maestro_driver_lib

extension AXElement {
    
    init(_ dict: [XCUIElement.AttributeName: Any])
    {
        func v(_ key: String) -> Any? { dict[XCUIElement.AttributeName(rawValue: key)] }

        // --- read raw fields ---
        let identifier          = v("identifier") as? String ?? ""
        let rawFrame            = (v("frame") as? AXFrame) ?? .zero
        let value               = v("value") as? String
        let title               = v("title") as? String
        let label               = v("label") as? String ?? ""
        let elementType         = v("elementType") as? Int ?? 0
        let enabled             = v("enabled") as? Bool ?? false
        let horizontalSizeClass = v("horizontalSizeClass") as? Int ?? 0
        let verticalSizeClass   = v("verticalSizeClass") as? Int ?? 0
        let placeholderValue    = v("placeholderValue") as? String
        let selected            = v("selected") as? Bool ?? false
        let hasFocus            = v("hasFocus") as? Bool ?? false
        let displayID           = v("displayID") as? Int ?? 0
        let windowContextID     = v("windowContextID") as? Double ?? 0

        // --- children ---
        let children: [AXElement]
        if let kids = v("children") as? [[XCUIElement.AttributeName: Any]] {
            children = kids.map {
                AXElement($0)
            }
        } else {
            children = []
        }

        // --- build ---
        self.init(
            identifier: identifier,
            frame: rawFrame,
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

@MainActor
struct ViewHierarchyHandler: HTTPHandler {

    private let springboardApplication = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private let snapshotMaxDepth = 60

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(ViewHierarchyRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided").httpResponse
        }

        do {
            let foregroundApp = RunningApp.getForegroundApp()
            guard let foregroundApp = foregroundApp else {
                NSLog("No foreground app found returning springboard app hierarchy")
                let (width, height, orientation) = try ScreenSizeProvider.actualScreenSize()
                let screenContext = ScreenContext(
                    deviceOrientation: orientation,
                    deviceWidth: CGFloat(width),
                    deviceHeight: CGFloat(height)
                )
                let springboardHierarchy = try elementHierarchy(xcuiElement: springboardApplication)
                let visualElement = ViewHierarchyProcessor.process(springboardHierarchy, screen: screenContext)
                let springBoardViewHierarchy = ViewHierarchy.init(axElement: springboardHierarchy, depth: springboardHierarchy.depth(), visualElement: visualElement)
                let body = try JSONEncoder().encode(springBoardViewHierarchy)
                return HTTPResponse(statusCode: .ok, body: body)
            }
            NSLog("[Start] View hierarchy snapshot for \(foregroundApp)")
            let appViewHierarchy = try logger.measure(message: "View hierarchy snapshot for \(foregroundApp)") {
                try getAppViewHierarchy(foregroundApp: foregroundApp, excludeKeyboardElements: requestBody.excludeKeyboardElements)
            }
            
            let (width, height, orientation) = try ScreenSizeProvider.actualScreenSize()
            let screenContext = ScreenContext(
                deviceOrientation: orientation,
                deviceWidth: CGFloat(width),
                deviceHeight: CGFloat(height)
            )
            let root = appViewHierarchy.children?.first ?? appViewHierarchy
            let orientedHierarchy = ViewHierarchyProcessor.process(root, screen: screenContext)
            let viewHierarchy = ViewHierarchy.init(
                axElement: appViewHierarchy,
                depth: orientedHierarchy.depth(),
                visualElement: orientedHierarchy
            )
            
            NSLog("[Done] View hierarchy snapshot for \(foregroundApp) ")
            let body = try JSONEncoder().encode(viewHierarchy)
            return HTTPResponse(statusCode: .ok, body: body)
        } catch let error as AppError {
            NSLog("AppError in handleRequest, Error:\(error)");
            return error.httpResponse
        } catch let error {
            NSLog("Error in handleRequest, Error:\(error)");
            return AppError(message: "Snapshot failure while getting view hierarchy. Error: \(error.localizedDescription)").httpResponse
        }
    }

    func getAppViewHierarchy(foregroundApp: XCUIApplication, excludeKeyboardElements: Bool) throws -> AXElement {
        SystemPermissionHelper.handleSystemPermissionAlertIfNeeded(foregroundApp: foregroundApp)
        let appHierarchy = try getHierarchyWithFallback(foregroundApp)
                
        let statusBars = logger.measure(message: "Fetch status bar hierarchy") {
            fullStatusBars(springboardApplication)
        } ?? []

        return AXElement(children: [appHierarchy, AXElement(children: statusBars)].compactMap { $0 })
    }

    func getHierarchyWithFallback(_ element: XCUIElement) throws -> AXElement {
        logger.info("Starting getHierarchyWithFallback for element.")

        do {
            var hierarchy = try elementHierarchy(xcuiElement: element)
            logger.info("Successfully retrieved element hierarchy.")

            if hierarchy.depth() < snapshotMaxDepth {
                return hierarchy
            }
            let count = try element.snapshot().children.count
            var children: [AXElement] = []
            for i in 0..<count {
              let element = element.descendants(matching: .other).element(boundBy: i).firstMatch
              children.append(try getHierarchyWithFallback(element))
            }
            hierarchy.children = children
            return hierarchy
        } catch let error {
            guard isIllegalArgumentError(error) else {
                NSLog("Snapshot failure, cannot return view hierarchy due to \(error)")
                if let nsError = error as NSError?,
                   nsError.domain == "com.apple.dt.XCTest.XCTFuture",
                   nsError.code == 1000,
                   nsError.localizedDescription.contains("Timed out while evaluating UI query") {
                    throw AppError(type: .timeout, message: error.localizedDescription)
                } else if let nsError = error as NSError?,
                           nsError.domain == "com.apple.dt.xctest.automation-support.error",
                           nsError.code == 6,
                           nsError.localizedDescription.contains("Unable to perform work on main run loop, process main thread busy for") {
                    throw AppError(type: .timeout, message: nsError.localizedDescription)
                } else {
                    throw AppError(message: error.localizedDescription)
                }
            }

            NSLog("Snapshot failure, getting recovery element for fallback")
            AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth
            // In apps with bigger view hierarchys, calling
            // `XCUIApplication().snapshot().dictionaryRepresentation` or `XCUIApplication().allElementsBoundByIndex`
            // throws "Error kAXErrorIllegalArgument getting snapshot for element <AXUIElementRef 0x6000025eb660>"
            // We recover by selecting the first child of the app element,
            // which should be the window, and continue from there.

            let recoveryElement = try findRecoveryElement(element.children(matching: .any).firstMatch)
            let hierarchy = try getHierarchyWithFallback(recoveryElement)

            // When the application element is skipped, try to fetch
            // the keyboard, alert and other custom element hierarchies separately.
            if let element = element as? XCUIApplication {
                let keyboard = logger.measure(message: "Fetch keyboard hierarchy") {
                    keyboardHierarchy(element)
                }

                let alerts = logger.measure(message: "Fetch alert hierarchy") {
                    fullScreenAlertHierarchy(element)
                }

                let other = try logger.measure(message: "Fetch other custom element from window") {
                    try customWindowElements(element)
                }
                return AXElement(children: [
                    other,
                    keyboard,
                    alerts,
                    hierarchy
                ].compactMap { $0 })
            }

            return hierarchy
        }
    }

    private func isIllegalArgumentError(_ error: Error) -> Bool {
        error.localizedDescription.contains("Error kAXErrorIllegalArgument getting snapshot for element")
    }

    private func keyboardHierarchy(_ element: XCUIApplication) -> AXElement? {
        guard element.keyboards.firstMatch.exists else {
            return nil
        }
        
        let keyboard = element.keyboards.firstMatch
        return try? elementHierarchy(xcuiElement: keyboard)
    }
    
    private func customWindowElements(_ element: XCUIApplication) throws -> AXElement? {
        let windowElement = element.children(matching: .any).firstMatch
        if try windowElement.snapshot().children.count > 1 {
            return nil
        }
        return try? elementHierarchy(xcuiElement: windowElement)
    }

    func fullScreenAlertHierarchy(_ element: XCUIApplication) -> AXElement? {
        guard element.alerts.firstMatch.exists else {
            return nil
        }
        
        let alert = element.alerts.firstMatch
        return try? elementHierarchy(xcuiElement: alert)
    }
    
    func fullStatusBars(_ element: XCUIApplication) -> [AXElement]? {
        guard element.statusBars.firstMatch.exists else {
            return nil
        }
        
        let snapshots = try? element.statusBars.allElementsBoundByIndex.compactMap{ (statusBar) in
            try elementHierarchy(xcuiElement: statusBar)
        }
        
        return snapshots
    }

    private func findRecoveryElement(_ element: XCUIElement) throws -> XCUIElement {
        if try element.snapshot().children.count > 1 {
            return element
        }
        let firstOtherElement = element.children(matching: .other).firstMatch
        if (firstOtherElement.exists) {
            return try findRecoveryElement(firstOtherElement)
        } else {
            return element
        }
    }
    
    private func elementHierarchy(xcuiElement: XCUIElement) throws -> AXElement {
        let snapshotDictionary = try xcuiElement.snapshot().dictionaryRepresentation
        return AXElement(snapshotDictionary)
    }
}
