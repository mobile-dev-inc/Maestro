#import <XCTest/XCTest.h>
#import "XCAccessibilityElement.h"

@interface AXClientProxy : NSObject

+ (instancetype)sharedClient;

- (NSArray<id<XCAccessibilityElement>> *)activeApplications;

- (NSDictionary *)defaultParameters;

@end
