import XCTest
import FlyingFox

final class ViewHierarchyHandlerTests: XCTestCase {
    
    override func setUpWithError() throws {
        let port = ProcessInfo.processInfo.environment["PORT"]?.toUInt16()
        if port != nil {
            throw XCTSkip("Running tests on cloud, skipping")
        }
        continueAfterFailure = false
        Task {
            try await startFlyingFoxServer()
        }
    }
    
    func startFlyingFoxServer() async throws {
        let server = HTTPServer(port: 8080)
        await server.appendRoute("hierarchy", to: ViewHierarchyHandler())
        try await server.run()
    }
    
    func testAppOffsetAdjustsCorrectly() async throws {
        // given
        let testApp = await XCUIApplication(bundleIdentifier: "org.wikimedia.wikipedia")
        await testApp.launch()
        guard let url = URL(string: "http://localhost:8080/hierarchy") else {
            throw NSError(domain: "XCTestError", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to construct URL"])
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(ViewHierarchyRequest(appIds: [], excludeKeyboardElements: false))
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let springboardApp = await XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let springboardFrame = await springboardApp.frame
        let testAppFrame = await testApp.frame
        
        let offsetX = springboardFrame.width - testAppFrame.width
        let offsetY = springboardFrame.height - testAppFrame.height
        let rawAppAXElement = try await AXElement(testApp.snapshot().dictionaryRepresentation)
        
        
        // when
        let (data, response) = try await URLSession.shared.data(for: request)
        let viewHierarchy = try JSONDecoder().decode(ViewHierarchy.self, from: data)
        let actualAppElement = viewHierarchy.axElement.children?.first
        
        
        // then
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertFalse(viewHierarchy.axElement.children?.isEmpty ?? true)
        
        let originalY = rawAppAXElement.frame["Y"] ?? 0
        let expectedY = originalY + offsetY
        let actualY = actualAppElement?.frame["Y"] ?? 0
        
        XCTAssertEqual(
            actualY,
            expectedY,
            accuracy: 0.5,
            "Y offset matches"
        )
        
        let originalX = rawAppAXElement.frame["X"] ?? 0
        let expectedX = originalX + offsetX
        let actualX = actualAppElement?.frame["X"] ?? 0
        XCTAssertEqual(
            actualX,
            expectedX,
            accuracy: 0.5,
            "X offset matches"
        )
    }
    
    func testAssertExpectedSnapshotRequestParameters() {
        // given
        let parameterDictionary = AXClientProxy.sharedClient().defaultParameters()
        
        // then
        // First, make sure the dictionary is not nil
        XCTAssertNotNil(parameterDictionary, "Parameter dictionary should not be nil")
        
        // Safely unwrap the optional dictionary
        guard let unwrappedDictionary = parameterDictionary else {
            XCTFail("Could not unwrap parameter dictionary because its nil")
            return
        }

        // Assert individual values
        XCTAssertEqual(unwrappedDictionary["maxChildren"] as? Int, 2147483647)
        XCTAssertEqual(unwrappedDictionary["maxDepth"] as? Int, 2147483647)
        XCTAssertEqual(unwrappedDictionary["maxArrayCount"] as? Int, 2147483647)
        XCTAssertEqual(unwrappedDictionary["traverseFromParentsToChildren"] as? Int, 1)
    }
}
