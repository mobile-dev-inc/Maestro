#import "RCTBridgeModule.h"

@interface RCT_EXTERN_MODULE(MaestroRnSdk, NSObject)

RCT_EXTERN_METHOD(setup:(NSString)projectId
                  withResolver:(RCTPromiseResolveBlock)resolve
                  withRejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(mockServerUrl:(NSString)baseUrl
                  withResolver:(RCTPromiseResolveBlock)resolve
                  withRejecter:(RCTPromiseRejectBlock)reject)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

@end
