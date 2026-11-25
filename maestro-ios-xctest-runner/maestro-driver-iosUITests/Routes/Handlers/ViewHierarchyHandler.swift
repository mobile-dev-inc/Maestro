import FlyingFox
import XCTest
import os
import MaestroDriverLib

@MainActor
struct ViewHierarchyHandler: HTTPHandler {

    private let springboardApplication = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private let snapshotMaxDepth = 60
    private let piecesTimeBudget: TimeInterval = 20
    private let pieceRetries = 2

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
        // On iOS 26 iPad, com.apple.DocumentManager.DockFolderViewService is reported as the
        // foreground process on the home screen, but its AX hierarchy is empty. The actual
        // home screen content lives in SpringBoard. Fall back to SpringBoard when detected.
        var foregroundApp = foregroundApp
        if foregroundApp.bundleID == "com.apple.DocumentManager.DockFolderViewService" {
            NSLog("DockFolderViewService detected as foreground, using SpringBoard hierarchy instead")
            foregroundApp = springboardApplication
        }
        let appHierarchy = try getHierarchyWithFallback(foregroundApp)
        await SystemPermissionHelper.handleSystemPermissionAlertIfNeeded(appHierarchy: appHierarchy, foregroundApp: foregroundApp)
                
        let statusBars = logger.measure(message: "Fetch status bar hierarchy") {
            fullStatusBars(springboardApplication)
        } ?? []
        
        // Fetch Safari WebView hierarchy for iOS 26+ (runs in separate SafariViewService process)
        let safariWebViewHierarchy = logger.measure(message: "Fetch Safari WebView hierarchy") {
            getSafariWebViewHierarchy()
        }

        // Fetch UI hosted from extension processes on iOS 26+ (e.g. the photo library picker)
        let hostedExtensionHierarchies = logger.measure(message: "Fetch hosted extension hierarchy") {
            getHostedExtensionHierarchies(appHierarchy: appHierarchy)
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
                return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 } + hostedExtensionHierarchies)
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
                return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 } + hostedExtensionHierarchies)
            }

            let offsetX = deviceWidth - appWidth
            let offsetY = deviceHeight - appHeight
            let offset = WindowOffset(offsetX: offsetX, offsetY: offsetY)

            NSLog("Adjusting view hierarchy with offset: \(offset)")

            let adjustedAppHierarchy = expandElementSizes(appHierarchy, offset: offset)

            return AXElement(children: [adjustedAppHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 } + hostedExtensionHierarchies)
        } else {
            return AXElement(children: [appHierarchy, AXElement(children: statusBars), safariWebViewHierarchy].compactMap { $0 } + hostedExtensionHierarchies)
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

    func getHierarchyWithFallback(_ element: XCUIApplication) throws -> AXElement {
        do {
            return try elementHierarchy(xcuiElement: element)
        } catch let error where isRecoverableSnapshotError(error) {
            NSLog("Snapshot failure (\(error.localizedDescription)), snapshotting in pieces")
            return try hierarchyInPieces(element)
        } catch let error {
            NSLog("Snapshot failure, cannot return view hierarchy due to \(error)")
            throw appError(from: error)
        }
    }

    /// Snapshots the app `snapshotMaxDepth` levels at a time through the AX client, which
    /// returns errors. XCTest queries fail on trees this deep and end the server's test.
    private func hierarchyInPieces(_ app: XCUIApplication) throws -> AXElement {
        guard let axElement = accessibilityElement(of: app) else {
            throw AppError(message: "No accessibility element to snapshot")
        }
        do {
            let snapshot = try AXClientProxy.sharedClient().snapshot(of: axElement, maxDepth: snapshotMaxDepth)
            let pieces = SnapshotPieces(maxDepth: snapshotMaxDepth, deadline: Date().addingTimeInterval(piecesTimeBudget))
            let hierarchy = elementHierarchy(snapshot: snapshot, inheritedOffset: .zero, parentWindowContextID: nil, pieces: pieces)
            if let timeout = pieces.timeout {
                NSLog("Snapshot in pieces timed out, returning a timeout so the host retries: \(timeout)")
                throw AppError(type: .timeout, message: timeout)
            }
            return hierarchy
        } catch let error as AppError {
            throw error
        } catch let error {
            NSLog("Snapshot failure in pieces, cannot return view hierarchy due to \(error)")
            throw appError(from: error)
        }
    }

    private func appError(from error: Error) -> AppError {
        if isTimeout(error) {
            return AppError(type: .timeout, message: error.localizedDescription)
        }
        return AppError(message: error.localizedDescription)
    }

    private func isTimeout(_ error: Error) -> Bool {
        (error as NSError).isXCUITestTimeout || error.localizedDescription.contains("kAXErrorCannotComplete")
    }

    /// Too deep to snapshot in one go, or an element went stale mid-snapshot.
    private func isRecoverableSnapshotError(_ error: Error) -> Bool {
        let description = error.localizedDescription
        return description.contains("Error kAXErrorIllegalArgument getting snapshot for element")
            || description.contains("kAXErrorInvalidUIElement")
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
        
        NSLog("[Start] Fetching Safari WebView hierarchy")
        let previousMaxDepth = AXClientSwizzler.overwriteDefaultParameters["maxDepth"]
        defer { AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = previousMaxDepth }
        do {
            AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth
            let safariHierarchy = try elementHierarchy(xcuiElement: safariWebService)
            // Checked on the snapshot: a `webViews.count` query that fails ends the server's test.
            guard containsWebView(safariHierarchy) else { return nil }
            NSLog("[Done] Safari WebView hierarchy fetched successfully")
            return safariHierarchy
        } catch {
            NSLog("[Error] Failed to fetch Safari WebView hierarchy: \(error.localizedDescription)")
            return nil
        }
    }

    private func containsWebView(_ element: AXElement) -> Bool {
        element.elementType == Int(XCUIElement.ElementType.webView.rawValue)
            || (element.children ?? []).contains(where: containsWebView)
    }

    /// Extension processes whose UI is hosted inside the app on iOS 26+.
    private static let hostedExtensionBundleIDs = [
        "com.apple.mobileslideshow.photo-picker", // UIImagePickerController photo library
        "com.apple.mobileslideshow.photospicker", // PHPickerViewController
    ]

    /// On iOS 26+ XCTest often leaves the photo pickers out of the app's snapshot
    /// (for example when one opens while the keyboard is up), so snapshot the
    /// extension itself unless the app already has it.
    private func getHostedExtensionHierarchies(appHierarchy: AXElement) -> [AXElement] {
        guard ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 26 else {
            return []
        }
        return Self.hostedExtensionBundleIDs.compactMap { bundleID in
            let hostedExtension = XCUIApplication(bundleIdentifier: bundleID)
            guard hostedExtension.state == .runningForeground else { return nil }
            // Cap the depth for this snapshot only, so later app snapshots keep theirs.
            let previousMaxDepth = AXClientSwizzler.overwriteDefaultParameters["maxDepth"]
            defer { AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = previousMaxDepth }
            do {
                AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth
                let hierarchy = try elementHierarchy(xcuiElement: hostedExtension)
                if HostedHierarchy.isIncluded(hierarchy, in: appHierarchy) {
                    return nil
                }
                NSLog("Added \(bundleID) hierarchy, which the app snapshot left out")
                return hierarchy
            } catch {
                NSLog("Failed to fetch \(bundleID) hierarchy: \(error.localizedDescription)")
                return nil
            }
        }
    }

    private func elementHierarchy(xcuiElement: XCUIElement) throws -> AXElement {
        let snapshot = try xcuiElement.snapshot()
        return elementHierarchy(snapshot: snapshot, inheritedOffset: .zero, parentWindowContextID: nil)
    }

    /// Walks the snapshot tree (not `dictionaryRepresentation`, which is O(subtree)
    /// per call) and accumulates a coordinate offset across cross-process window
    /// boundaries so descendant frames land in screen coordinates.
    private func elementHierarchy(
        snapshot: XCUIElementSnapshot,
        inheritedOffset: CGVector,
        parentWindowContextID: Double?,
        pieces: SnapshotPieces? = nil,
        depth: Int = 0
    ) -> AXElement {
        let rawFrame = axFrame(from: snapshot.frame)
        let windowContextID = kvcDouble(snapshot, "windowContextID") ?? 0

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

        var childSnapshots = snapshot.children
        var childDepth = depth + 1
        if let pieces, childSnapshots.isEmpty, depth >= pieces.maxDepth - 1,
           let nextPiece = nextPiece(below: snapshot, pieces) {
            childSnapshots = nextPiece.children
            childDepth = 1
        }
        let children = childSnapshots.map { child in
            elementHierarchy(
                snapshot: child,
                inheritedOffset: currentOffset,
                parentWindowContextID: windowContextID,
                pieces: pieces,
                depth: childDepth
            )
        }

        return AXElement(
            identifier: snapshot.identifier,
            frame: offsetFrame(rawFrame, by: currentOffset),
            value: snapshot.value as? String,
            title: snapshot.title,
            label: snapshot.label,
            elementType: Int(snapshot.elementType.rawValue),
            enabled: snapshot.isEnabled,
            horizontalSizeClass: snapshot.horizontalSizeClass.rawValue,
            verticalSizeClass: snapshot.verticalSizeClass.rawValue,
            placeholderValue: snapshot.placeholderValue,
            selected: snapshot.isSelected,
            hasFocus: snapshot.hasFocus,
            displayID: kvcInt(snapshot, "displayID") ?? 0,
            windowContextID: windowContextID,
            children: children
        )
    }

    private final class SnapshotPieces {
        let maxDepth: Int
        let deadline: Date
        /// Set when a piece kept timing out or time ran out. No more pieces are fetched.
        var timeout: String?

        init(maxDepth: Int, deadline: Date) {
            self.maxDepth = maxDepth
            self.deadline = deadline
        }
    }

    /// A piece that fails for another reason is left out. A piece that times out is
    /// retried `pieceRetries` times; after that, or once time is up, the request times out.
    private func nextPiece(below snapshot: XCUIElementSnapshot, _ pieces: SnapshotPieces) -> XCUIElementSnapshot? {
        guard pieces.timeout == nil, let axElement = accessibilityElement(of: snapshot) else { return nil }
        var lastTimeout = ""
        for attempt in 0...pieceRetries {
            guard Date() < pieces.deadline else {
                pieces.timeout = "Ran out of time snapshotting the hierarchy in pieces"
                return nil
            }
            do {
                return try AXClientProxy.sharedClient().snapshot(of: axElement, maxDepth: pieces.maxDepth)
            } catch let error where isTimeout(error) {
                NSLog("Snapshot piece timed out (try \(attempt + 1) of \(pieceRetries + 1)): \(error.localizedDescription)")
                lastTimeout = error.localizedDescription
            } catch {
                NSLog("Could not snapshot below the depth limit (\(error.localizedDescription)), leaving it out")
                return nil
            }
        }
        pieces.timeout = lastTimeout
        return nil
    }

    private func accessibilityElement(of app: XCUIApplication) -> AnyObject? {
        let pid = XCUIApplication.activeAppsInfo()
            .first { $0["bundleId"] as? String == app.bundleID }?["pid"] as? NSNumber
        guard let pid else { return nil }
        return AXClientProxy.sharedClient().accessibilityElement(processIdentifier: pid.int32Value) as AnyObject?
    }

    private func accessibilityElement(of snapshot: XCUIElementSnapshot) -> AnyObject? {
        guard let snapshot = snapshot as? NSObject,
              snapshot.responds(to: NSSelectorFromString("accessibilityElement")) else {
            return nil
        }
        return snapshot.value(forKey: "accessibilityElement") as AnyObject?
    }

    /// Offset to apply to descendant frames when crossing into a cross-process
    /// window (e.g. HealthKit/share sheet). All three signals — windowContextID
    /// transition, remote subtree, finite visibleFrame — must align; the remote
    /// check guards against in-process boundaries like UITextEffectsWindow where
    /// a non-zero visibleFrame delta is ordinary clipping.
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

    private func axFrame(from rect: CGRect) -> AXFrame {
        return [
            "X": Double(rect.origin.x),
            "Y": Double(rect.origin.y),
            "Width": Double(rect.size.width),
            "Height": Double(rect.size.height)
        ]
    }

    private func kvcDouble(_ snapshot: XCUIElementSnapshot, _ key: String) -> Double? {
        guard let object = snapshot as? NSObject,
              object.responds(to: NSSelectorFromString(key)) else {
            return nil
        }
        return (object.value(forKey: key) as? NSNumber)?.doubleValue
    }

    private func kvcInt(_ snapshot: XCUIElementSnapshot, _ key: String) -> Int? {
        guard let object = snapshot as? NSObject,
              object.responds(to: NSSelectorFromString(key)) else {
            return nil
        }
        return (object.value(forKey: key) as? NSNumber)?.intValue
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
