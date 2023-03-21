import Foundation

public struct MaestroSdk {
    
    @available(*, unavailable) private init() {}
    
    static var projectId: String? = nil
    
    public static func setup(projectId: String) {
        MaestroSdk.projectId = projectId
    }
    
    public static func mockServer() -> MaestroMockServerSdk {
        return MaestroMockServerSdk()
    }
    
}
