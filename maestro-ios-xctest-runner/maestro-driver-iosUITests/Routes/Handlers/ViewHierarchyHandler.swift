import FlyingFox
import XCTest
import os
import MaestroDriverLib

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
                let springboardHierarchy = try elementHierarchy(xcuiElement: springboardApplication)
                let springBoardViewHierarchy = ViewHierarchy.init(axElement: springboardHierarchy, depth: springboardHierarchy.depth())
                let body = try JSONEncoder().encode(springBoardViewHierarchy)
                return HTTPResponse(statusCode: .ok, body: body)
            }
            NSLog("[Start] View hierarchy snapshot for \(foregroundApp)")
            let appViewHierarchy = try await getAppViewHierarchy(foregroundApp: foregroundApp, excludeKeyboardElements: requestBody.excludeKeyboardElements)
            let viewHierarchy = ViewHierarchy.init(axElement: appViewHierarchy, depth: appViewHierarchy.depth())
            
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

    func getAppViewHierarchy(foregroundApp: XCUIApplication, excludeKeyboardElements: Bool) async throws -> AXElement {
        let appHierarchy = try getHierarchyWithFallback(foregroundApp)
        await SystemPermissionHelper.handleSystemPermissionAlertIfNeeded(appHierarchy: appHierarchy, foregroundApp: foregroundApp)
                
        let statusBars = logger.measure(message: "Fetch status bar hierarchy") {
            fullStatusBars(springboardApplication)
        } ?? []
        
        // Fetch Safari WebView hierarchy for iOS 26+ (runs in separate SafariViewService process)
        let safariWebViewHierarchy = logger.measure(message: "Fetch Safari WebView hierarchy") {
            getSafariWebViewHierarchy()
        }

        let deviceFrame = springboardApplication.frame
        let deviceAxFrame = [
            "X": Double(deviceFrame.minX),
            "Y": Double(deviceFrame.minY),
            "Width": Double(deviceFrame.width),
            "Height": Double(deviceFrame.height)
        ]
        let appFrame = appHierarchy.frame
        
        if deviceAxFrame != appFrame {
            guard
                let deviceWidth = deviceAxFrame["Width"], deviceWidth > 0,
                let deviceHeight = deviceAxFrame["Height"], deviceHeight > 0,
                let appWidth = appFrame["Width"], appWidth > 0,
                let appHeight = appFrame["Height"], appHeight > 0
            else {
                return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
            }

            // Springboard always reports its frame in portrait dimensions (e.g. 1024×1366),
            // while a landscape app reports them swapped (1366×1024). Without this guard,
            // the difference would be misinterpreted as a window offset, shifting every
            // element's coordinates by hundreds of points in the wrong direction.
            let isSameAreaDifferentOrientation =
                abs(deviceWidth * deviceHeight - appWidth * appHeight) < 1.0
                && abs(deviceWidth - appHeight) < 1.0
                && abs(deviceHeight - appWidth) < 1.0

            if isSameAreaDifferentOrientation {
                NSLog("Skipping offset adjustment: device and app frames are same size but different orientation")
                return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
            }

            let offsetX = deviceWidth - appWidth
            let offsetY = deviceHeight - appHeight
            let offset = WindowOffset(offsetX: offsetX, offsetY: offsetY)

            NSLog("Adjusting view hierarchy with offset: \(offset)")

            let adjustedAppHierarchy = expandElementSizes(appHierarchy, offset: offset)

            return AXElement(children: [adjustedAppHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
        } else {
            return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 })
        }
    }
    
    func expandElementSizes(_ element: AXElement, offset: WindowOffset) -> AXElement {
        let adjustedFrame: AXFrame = [
            "X": (element.frame["X"] ?? 0) + offset.offsetX,
            "Y": (element.frame["Y"] ?? 0) + offset.offsetY,
            "Width": element.frame["Width"] ?? 0,
            "Height": element.frame["Height"] ?? 0
        ]
        let adjustedChildren = element.children?.map { expandElementSizes($0, offset: offset) } ?? []
        
        return AXElement(
            identifier: element.identifier,
            frame: adjustedFrame,
            value: element.value,
            title: element.title,
            label: element.label,
            elementType: element.elementType,
            enabled: element.enabled,
            horizontalSizeClass: element.horizontalSizeClass,
            verticalSizeClass: element.verticalSizeClass,
            placeholderValue: element.placeholderValue,
            selected: element.selected,
            hasFocus: element.hasFocus,
            displayID: element.displayID,
            windowContextID: element.windowContextID,
            children: adjustedChildren
        )
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
    
    /// Fetches the Safari WebView hierarchy for iOS 26+ where SFSafariViewController
    /// runs in a separate process (com.apple.SafariViewService).
    /// Returns nil if not on iOS 26+, Safari service is not running, or no webviews exist.
    private func getSafariWebViewHierarchy() -> AXElement? {
        let systemVersion = ProcessInfo.processInfo.operatingSystemVersion
        guard systemVersion.majorVersion >= 26 else {
            return nil
        }
        
        let safariWebService = XCUIApplication(bundleIdentifier: "com.apple.SafariViewService")
        
        let isRunning = safariWebService.state == .runningForeground || safariWebService.state == .runningBackground
        guard isRunning else {
            return nil
        }
        
        let webViewCount = safariWebService.webViews.count
        guard webViewCount > 0 else {
            return nil
        }
        
        NSLog("[Start] Fetching Safari WebView hierarchy (\(webViewCount) webview(s) detected)")
        
        do {
            AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth
            let safariHierarchy = try elementHierarchy(xcuiElement: safariWebService)
            NSLog("[Done] Safari WebView hierarchy fetched successfully")
            return safariHierarchy
        } catch {
            NSLog("[Error] Failed to fetch Safari WebView hierarchy: \(error.localizedDescription)")
            return nil
        }
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
        let snapshot = try xcuiElement.snapshot()
        return elementHierarchy(snapshot: snapshot, inheritedOffset: .zero, parentWindowContextID: nil)
    }

    /// Recursively converts an `XCUIElementSnapshot` into Maestro's `AXElement` tree.
    ///
    /// At each cross-process window boundary we accumulate a coordinate-system offset
    /// (see `crossProcessWindowOffset`) and inherit it down the subtree so descendant
    /// frames are reported in screen coordinates rather than the foreign window's
    /// local coordinates.
    private func elementHierarchy(
        snapshot: XCUIElementSnapshot,
        inheritedOffset: CGVector,
        parentWindowContextID: Double?
    ) -> AXElement {
        let dictionary = snapshot.dictionaryRepresentation
        let rawFrame = frame(dictionary)
        let windowContextID = (dictionary[XCUIElement.AttributeName(rawValue: "windowContextID")] as? Double) ?? 0

        let boundaryOffset = crossProcessWindowOffset(
            snapshot: snapshot,
            rawFrame: rawFrame,
            parentWindowContextID: parentWindowContextID,
            windowContextID: windowContextID
        )
        let currentOffset = CGVector(
            dx: inheritedOffset.dx + boundaryOffset.dx,
            dy: inheritedOffset.dy + boundaryOffset.dy
        )

        var element = AXElement(dictionary, frameOverride: offsetFrame(rawFrame, by: currentOffset))
        element.children = snapshot.children.map { child in
            elementHierarchy(snapshot: child, inheritedOffset: currentOffset, parentWindowContextID: windowContextID)
        }
        return element
    }

    /// Returns the screen-space offset that needs to be applied to descendant frames
    /// when crossing into a cross-process window subtree. Returns `.zero` for any
    /// boundary that does not match the cross-process pattern, even when other
    /// signals (e.g. visibleFrame clipping) would otherwise produce a non-zero delta.
    ///
    /// The fix targets a specific iOS bug: when a system sheet (HealthKit, share
    /// sheet, photo picker, etc.) is presented, XCTest stitches the foreign
    /// process's view tree into the host application's snapshot, but the foreign
    /// process reports frames in its own window's local coordinates rather than
    /// screen coordinates. This shows up as:
    ///
    ///   - The host app's `windowContextID` differing from the descendant subtree's
    ///   - The descendant subtree being marked `isRemote = 1`
    ///   - `visibleFrame.origin` matching the actual on-screen position while
    ///     `frame.origin` reports the (wrong) window-local position
    ///
    /// All three signals must align before we apply the correction. Requiring the
    /// remote-subtree signal (in addition to the windowContextID transition) guards
    /// against benign in-process boundaries (e.g. `UITextEffectsWindow`) where a
    /// non-zero `visibleFrame` delta would represent ordinary clipping, not a
    /// coordinate-system mismatch.
    private func crossProcessWindowOffset(
        snapshot: XCUIElementSnapshot,
        rawFrame: AXFrame,
        parentWindowContextID: Double?,
        windowContextID: Double
    ) -> CGVector {
        guard isCrossWindowContextBoundary(parentWindowContextID: parentWindowContextID, windowContextID: windowContextID),
              containsRemoteSubtree(snapshot),
              let visibleFrame = visibleFrame(snapshot) else {
            return .zero
        }

        return CGVector(
            dx: visibleFrame.x - rawFrame.x,
            dy: visibleFrame.y - rawFrame.y
        )
    }

    private func isCrossWindowContextBoundary(parentWindowContextID: Double?, windowContextID: Double) -> Bool {
        guard let parentWindowContextID = parentWindowContextID else {
            return false
        }
        return parentWindowContextID != 0
            && windowContextID != 0
            && parentWindowContextID != windowContextID
    }

    private func containsRemoteSubtree(_ snapshot: XCUIElementSnapshot) -> Bool {
        if isRemote(snapshot) { return true }
        return snapshot.children.contains(where: isRemote)
    }

    private func isRemote(_ snapshot: XCUIElementSnapshot) -> Bool {
        guard let snapshotObject = snapshot as? NSObject,
              snapshotObject.responds(to: NSSelectorFromString("isRemote")) else {
            return false
        }
        return (snapshotObject.value(forKey: "isRemote") as? NSNumber)?.boolValue ?? false
    }

    private func visibleFrame(_ snapshot: XCUIElementSnapshot) -> AXFrame? {
        guard let snapshotObject = snapshot as? NSObject,
              snapshotObject.responds(to: NSSelectorFromString("visibleFrame")),
              let value = snapshotObject.value(forKey: "visibleFrame") as? NSValue else {
            return nil
        }

        let rect = value.cgRectValue
        guard rect.origin.x.isFinite,
              rect.origin.y.isFinite,
              rect.size.width.isFinite,
              rect.size.height.isFinite else {
            return nil
        }

        return [
            "X": Double(rect.origin.x),
            "Y": Double(rect.origin.y),
            "Width": Double(rect.size.width),
            "Height": Double(rect.size.height)
        ]
    }

    private func frame(_ dictionary: [XCUIElement.AttributeName: Any]) -> AXFrame {
        dictionary[XCUIElement.AttributeName(rawValue: "frame")] as? AXFrame ?? .zero
    }

    private func offsetFrame(_ frame: AXFrame, by offset: CGVector) -> AXFrame {
        guard offset.dx != 0 || offset.dy != 0 else {
            return frame
        }
        return [
            "X": frame.x + Double(offset.dx),
            "Y": frame.y + Double(offset.dy),
            "Width": frame.width,
            "Height": frame.height
        ]
    }
}
