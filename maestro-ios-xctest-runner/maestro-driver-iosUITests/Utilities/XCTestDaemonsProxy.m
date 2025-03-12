#import "XCTestDaemonsProxy.h"
#import "FBLogger.h"
#import "XCTRunnerDaemonSession.h"

@implementation XCTestDaemonsProxy

+ (id<XCTestManager_ManagerInterface>)testRunnerProxy
{
    static id<XCTestManager_ManagerInterface> proxy = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        [FBLogger logFmt:@"Using singleton test manager"];
        proxy = [self.class retrieveTestRunnerProxy];
    });
    return proxy;
}

+ (id<XCTestManager_ManagerInterface>)retrieveTestRunnerProxy
{
    return ((XCTRunnerDaemonSession *)[XCTRunnerDaemonSession sharedSession]).daemonProxy;
}


@end
