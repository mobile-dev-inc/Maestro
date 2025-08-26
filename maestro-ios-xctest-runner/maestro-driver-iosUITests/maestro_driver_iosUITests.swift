import XCTest
import FlyingFox
import os

final class maestro_driver_iosUITests: XCTestCase {
   
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: "maestro_driver_iosUITests"
    )

    private static var swizzledOutIdle = false

    override func setUpWithError() throws {
        // XCTest internals sometimes use XCTAssert* instead of exceptions.
        // Setting `continueAfterFailure` so that the xctest runner does not stop
        // when an XCTest internal error happes (eg: when using .allElementsBoundByIndex
        // on a ReactNative app)
        continueAfterFailure = true
    }

    override class func setUp() {
        logger.trace("setUp")
    }

    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        maestro_driver_iosUITests.logger.info("Will start HTTP server")
        try await server.start()
    }
    
    func testLandscape() async throws {
        let app = await XCUIApplication(bundleIdentifier: "org.wikimedia.wikipedia")
        
//        let elementDepth = AXElement(try! await app.snapshot().dictionaryRepresentation).depth()
        print(AXElement(try! await RunningApp.getForegroundApp()!.snapshot().dictionaryRepresentation))
    }

    override class func tearDown() {
        logger.trace("tearDown")
    }
    
//    func testDeviceInfo() {
//        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
//        let wikipedia = XCUIApplication(bundleIdentifier: "org.wikimedia.wikipedia")
//        let springboardSize = springboard.frame.size
//        let wikipediaSize = wikipedia.frame.size
//        let frame = (Float(springboardSize.width), Float(springboardSize.height))
//        
//        print("Springboard: \(frame)")
//        print("Wikipedia: \(wikipediaSize)")
//        
//        // Potrait:
//        // Springboard: (393.0, 852.0)
//        // Wikipedia: (393.0, 852.0)
//        
//        // Landscape:
//        // Springboard: (393.0, 852.0)
//        // Wikipedia: (852.0, 393.0)
//        
//    }
}
