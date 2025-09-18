import XCTest

@testable import maestro_driver_lib

final class maestro_driver_libTests: XCTestCase {
    
    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.
    }
    
    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }
    
    func testPortraitInput_PortraitDevice_NotRotated() throws {
        // given
        let portrait = loadFixture("portrait_screenshot")
        
        // when
        let data = DefaultScreenshotPreprocessor().process(
            portrait,
            deviceOrientation: .portrait,
            output: .png
        )
        
        // then
        XCTAssertNotNil(data)
        let out = UIImage(data: data!)!
        // Pixels are normalized to .up and size unchanged
        XCTAssertEqual(out.imageOrientation, .up)
        XCTAssertEqual(Int(out.size.width),  Int(portrait.size.width))
        XCTAssertEqual(Int(out.size.height), Int(portrait.size.height))
    }
    
    func testPortraitInput_LandscapeRight_Rotates90() throws {
        // given
        let portrait = loadFixture("portrait_screenshot")
        
        // when
        let data = DefaultScreenshotPreprocessor()
            .process(portrait, deviceOrientation: .landscapeRight, output: .png)
        
        // then
        XCTAssertNotNil(data)
        let out = UIImage(data: data!)!
        // Rotation baked into pixels; width/height swap
        XCTAssertEqual(Int(out.size.width),  Int(portrait.size.height))
        XCTAssertEqual(Int(out.size.height), Int(portrait.size.width))
    }
    
    
    
    private func loadFixture(_ name: String, ext: String = "png") -> UIImage {
        let bundle = Bundle(for: Self.self)
        
        let url = bundle.url(
            forResource: name,
            withExtension: ext,
            subdirectory: "Fixtures"
        ) ?? bundle.url(forResource: name, withExtension: ext)
        
        guard let url, let img = UIImage(contentsOfFile: url.path) else {
            XCTFail("Missing test fixture: Fixtures/\(name).\(ext)")
            return UIImage()
        }
        return img
    }
}
