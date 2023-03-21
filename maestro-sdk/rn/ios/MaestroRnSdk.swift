import Foundation
import MaestroSDK

@objc(MaestroRnSdk)
class MaestroRnSdk: NSObject {
    
    @objc(setup:withResolver:withRejecter:)
    func setup(
        projectId: String,
        resolve:RCTPromiseResolveBlock,
        reject:RCTPromiseRejectBlock
    ) -> Void {
        MaestroSdk.setup(projectId: projectId)
        resolve(true)
    }
    
    @objc(mockServerUrl:withResolver:withRejecter:)
    func mockServerUrl(
        baseUrl: String,
        resolve:RCTPromiseResolveBlock,
        reject:RCTPromiseRejectBlock
    ) -> Void {
        resolve(
            MaestroSdk.mockServer().url(baseUrl: baseUrl)
        )
    }
    
}
