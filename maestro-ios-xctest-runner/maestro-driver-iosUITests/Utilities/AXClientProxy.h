#import <XCTest/XCTest.h>
#import "XCAccessibilityElement.h"

@interface AXClientProxy : NSObject

+ (instancetype)sharedClient;

- (NSArray<id<XCAccessibilityElement>> *)activeApplications;

- (NSDictionary *)defaultParameters;

- (nullable id)accessibilityElementForProcessIdentifier:(pid_t)processIdentifier
    NS_SWIFT_NAME(accessibilityElement(processIdentifier:));

/// Returns errors instead of recording a test failure.
- (nullable id<XCUIElementSnapshot>)snapshotForElement:(id)element
                                              maxDepth:(NSInteger)maxDepth
                                                 error:(NSError **)error
    NS_SWIFT_NAME(snapshot(of:maxDepth:));

@end
