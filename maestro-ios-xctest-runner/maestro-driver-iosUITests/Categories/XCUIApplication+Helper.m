#import "XCUIApplication+Helper.h"
#import "AXClientProxy.h"
#import "FBLogger.h"
#import "XCTestDaemonsProxy.h"
#import "XCAccessibilityElement.h"
#import "XCTestManager_ManagerInterface-Protocol.h"

@implementation XCUIApplication (Helper)

+ (NSArray<NSDictionary<NSString *, id> *> *)appsInfoWithAxElements:(NSArray<id<XCAccessibilityElement>> *)axElements
{
    NSMutableArray<NSDictionary<NSString *, id> *> *result = [NSMutableArray array];
    id<XCTestManager_ManagerInterface> proxy = [XCTestDaemonsProxy testRunnerProxy];
    for (id<XCAccessibilityElement> axElement in axElements) {
        NSMutableDictionary<NSString *, id> *appInfo = [NSMutableDictionary dictionary];
        pid_t pid = axElement.processIdentifier;
        appInfo[@"pid"] = @(pid);
        __block NSString *bundleId = nil;
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);
        [proxy _XCT_requestBundleIDForPID:pid
                                    reply:^(NSString *bundleID, NSError *error) {
            if (nil == error) {
                bundleId = bundleID;
            } else {
                [FBLogger logFmt:@"Cannot request the bundle ID for process ID %@: %@", @(pid), error.description];
            }
            dispatch_semaphore_signal(sem);
        }];
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1 * NSEC_PER_SEC)));
        appInfo[@"bundleId"] = bundleId ?: @"unknowBundleId";
        [result addObject:appInfo.copy];
    }
    return result.copy;
}

+ (NSArray<NSDictionary<NSString *, id> *> *)activeAppsInfo
{
    return [self appsInfoWithAxElements:[AXClientProxy.sharedClient activeApplications]];
}

@end
