#import "AXClientProxy.h"
#import "XCAccessibilityElement.h"
#import "XCUIDevice.h"

static id AXClient = nil;

@implementation AXClientProxy

+ (instancetype)sharedClient
{
    static AXClientProxy *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[self alloc] init];
        AXClient = [XCUIDevice.sharedDevice accessibilityInterface];
    });
    return instance;
}

- (NSArray<id<XCAccessibilityElement>> *)activeApplications
{
    return [AXClient activeApplications];
}

- (NSDictionary *)defaultParameters {
    return [AXClient defaultParameters];
}

@end
