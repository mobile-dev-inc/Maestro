#import "XCUIApplication+Helper.h"
#import "AXClientProxy.h"
#import "FBLogger.h"
#import "XCTestDaemonsProxy.h"
#import "XCAccessibilityElement.h"
#import "XCTestManager_ManagerInterface-Protocol.h"
#import <objc/runtime.h>

@implementation XCUIApplication (Helper)

- (BOOL)maestro_doesNotHandleUIInterruptions
{
    return YES;
}

+ (void)load
{
    SEL originalSelector = @selector(doesNotHandleUIInterruptions);
    SEL swizzledSelector = @selector(maestro_doesNotHandleUIInterruptions);
    Method originalMethod = class_getInstanceMethod(self.class, originalSelector);
    Method swizzledMethod = class_getInstanceMethod(self.class, swizzledSelector);
    if (originalMethod == NULL || swizzledMethod == NULL) {
        [FBLogger log:@"Cannot swizzle -[XCUIApplication doesNotHandleUIInterruptions]: method not found"];
        return;
    }
    BOOL didAddMethod = class_addMethod(self.class,
                                        originalSelector,
                                        method_getImplementation(swizzledMethod),
                                        method_getTypeEncoding(swizzledMethod));
    if (didAddMethod) {
        class_replaceMethod(self.class,
                            swizzledSelector,
                            method_getImplementation(originalMethod),
                            method_getTypeEncoding(originalMethod));
    } else {
        method_exchangeImplementations(originalMethod, swizzledMethod);
    }
}

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
