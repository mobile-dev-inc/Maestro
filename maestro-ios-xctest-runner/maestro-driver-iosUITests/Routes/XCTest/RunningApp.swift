import Foundation
import XCTest
import os

struct RunningApp {
    
    private static let springboardBundleId = "com.apple.springboard"
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    private init() {}
    
    static func getForegroundAppId(_ appIds: [String]) -> String {
        if appIds.isEmpty {
            logger.info("Empty installed apps found")
            return ""
        }
        
        return appIds.first { appId in
            let app = XCUIApplication(bundleIdentifier: appId)
            
            return app.state == .runningForeground
        } ?? RunningApp.springboardBundleId
    }
    
    static func getForegroundAppIds(_ appIds: [String]) -> [String] {
        // springboard is always on foreground
        let allAppIds = appIds + ["com.apple.springboard"]
        
            
        return allAppIds.filter { appId in
            let app = XCUIApplication(bundleIdentifier: appId)
            return app.state == .runningForeground
        }
    }
}
