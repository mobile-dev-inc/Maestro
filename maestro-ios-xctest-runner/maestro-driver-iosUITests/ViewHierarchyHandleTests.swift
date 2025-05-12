import XCTest
import FlyingFox

final class ViewHierarchyHandlerTests: XCTestCase {
    
    override func setUpWithError() throws {
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
    
    func testAppFrameGetsScaledCorrectly() async throws {
        // given
        guard let url = URL(string: "http://localhost:8080/hierarchy") else {
            throw NSError(domain: "XCTestError", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to construct URL"])
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(ViewHierarchyRequest(appIds: [], excludeKeyboardElements: false))
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let springboardApp = await XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let testApp = await XCUIApplication(bundleIdentifier: "org.wikimedia.wikipedia")
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
            "Scaled app element height matches"
        )
        
        let originalX = rawAppAXElement.frame["X"] ?? 0
        let expectedX = originalX + offsetX
        let actualX = actualAppElement?.frame["X"] ?? 0
        XCTAssertEqual(
            actualX,
            expectedX,
            accuracy: 0.5,
            "Scaled app element width matches"
        )
    }
}
