#import <XCTest/XCTest.h>
#import "XCSynthesizedEventRecord.h"


@protocol XCTestManager_ManagerInterface;

@interface XCTestDaemonsProxy : NSObject

+ (id<XCTestManager_ManagerInterface>)testRunnerProxy;


@end
