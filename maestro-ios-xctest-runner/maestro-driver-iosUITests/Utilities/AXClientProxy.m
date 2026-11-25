#import "AXClientProxy.h"
#import "XCAccessibilityElement.h"
#import "XCUIDevice.h"
#import "XCAXClient_iOS.h"

static id AXClient = nil;

static NSArray *snapshotAttributes(void)
{
    static NSArray *attributes = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class snapshotClass = NSClassFromString(@"XCElementSnapshot");
        SEL selector = NSSelectorFromString(@"sanitizedElementSnapshotHierarchyAttributesForAttributes:isMacOS:");
        if ([snapshotClass respondsToSelector:selector]) {
            id (*sanitized)(id, SEL, id, BOOL) = (void *)[snapshotClass methodForSelector:selector];
            id result = sanitized(snapshotClass, selector, nil, NO);
            attributes = [result isKindOfClass:NSSet.class] ? [result allObjects] : result;
        }
    });
    return attributes;
}

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

- (id)accessibilityElementForProcessIdentifier:(pid_t)processIdentifier
{
    for (id<XCAccessibilityElement> element in [AXClient activeApplications]) {
        if (element.processIdentifier == processIdentifier) {
            return element;
        }
    }
    return nil;
}

- (id<XCUIElementSnapshot>)snapshotForElement:(id)element
                                     maxDepth:(NSInteger)maxDepth
                                        error:(NSError **)error
{
    NSMutableDictionary *parameters = [[AXClient defaultParameters] mutableCopy];
    parameters[@"maxDepth"] = @(maxDepth);
    id result = [AXClient requestSnapshotForElement:element
                                         attributes:snapshotAttributes()
                                         parameters:parameters
                                              error:error];
    // Newer Xcodes wrap the snapshot in a request result.
    if ([result respondsToSelector:NSSelectorFromString(@"rootElementSnapshot")]) {
        result = [result valueForKey:@"rootElementSnapshot"];
    }
    return result;
}

@end
