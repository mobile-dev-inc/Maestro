/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import "XCAXClient_iOS+FBSnapshotReqParams.h"

#import <objc/runtime.h>

/**
 Available parameters with their default values for XCTest:
  @"maxChildren" : (int)2147483647
  @"traverseFromParentsToChildren" : YES
  @"maxArrayCount" : (int)2147483647
  @"snapshotKeyHonorModalViews" : NO
  @"maxDepth" : (int)2147483647
 */
NSString *const FBSnapshotMaxDepthKey = @"maxDepth";

static id (*original_defaultParameters)(id, SEL);
static id (*original_snapshotParameters)(id, SEL);
static NSDictionary *defaultRequestParameters;
static NSDictionary *defaultAdditionalRequestParameters;
static NSMutableDictionary *customRequestParameters;
static BOOL swizzlingPerformed = NO;
static Method originalMethodMan;
static Method orirgin;

static id (*original_defaultParameters)(id, SEL);
static id (*original_snapshotParameters)(id, SEL);
static NSDictionary *defaultRequestParameters;
static NSDictionary *defaultAdditionalRequestParameters;
static NSMutableDictionary *customRequestParameters;

static id swizzledDefaultParameters(id self, SEL _cmd) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
      defaultRequestParameters = original_defaultParameters(self, _cmd);
    });
    NSMutableDictionary *result =
        [NSMutableDictionary dictionaryWithDictionary:defaultRequestParameters];
    [result addEntriesFromDictionary:defaultAdditionalRequestParameters ?: @{}];
    [result addEntriesFromDictionary:customRequestParameters ?: @{}];
    return result.copy;
}

static id swizzledSnapshotParameters(id self, SEL _cmd) {
    NSDictionary *result = original_snapshotParameters(self, _cmd);
    defaultAdditionalRequestParameters = result;
    return result;
}

// Separate function for swizzling setup
static void FBPerformSnapshotSwizzlingIfNeeded(void) {
    // Initialize the dictionary
    customRequestParameters = [NSMutableDictionary new];
    
    // Swizzle defaultParameters
    Method original_defaultParametersMethod =
        class_getInstanceMethod(NSClassFromString(@"XCAXClient_iOS"), @selector(defaultParameters));
    original_defaultParameters = (id(*)(id, SEL))method_getImplementation(original_defaultParametersMethod);
    method_setImplementation(original_defaultParametersMethod, (IMP)swizzledDefaultParameters);
    
    // Swizzle snapshotParameters
    Method original_snapshotParametersMethod =
        class_getInstanceMethod(NSClassFromString(@"XCTElementQuery"),
                        NSSelectorFromString(@"snapshotParameters"));
    original_snapshotParameters = (id(*)(id, SEL))method_getImplementation(original_snapshotParametersMethod);
    method_setImplementation(original_snapshotParametersMethod, (IMP)swizzledSnapshotParameters);
    
    swizzlingPerformed = YES;
}

void FBDisableHonorModalViews(void) {
    FBSetCustomParameterForElementSnapshot(@"snapshotKeyHonorModalViews", @NO);
}

// Set a custom parameter for element snapshot
void FBSetCustomParameterForElementSnapshot(NSString *name, id value) {
    // Ensure swizzling is performed
    FBPerformSnapshotSwizzlingIfNeeded();
    
    // Set parameter
    customRequestParameters[name] = value;
    
    // Force parameters to update (important!)
    id axClient = [[NSClassFromString(@"XCAXClient_iOS") alloc] init];
    [axClient defaultParameters];
    
    NSLog(@"Parameter set - %@: %@, Current parameters: %@", name, value, [axClient defaultParameters]);
}
\
void FBResetAllCustomParameters(void) {
    // Reset our custom parameters dictionary
    FBPerformSnapshotSwizzlingIfNeeded();
    
    [customRequestParameters removeAllObjects];
    
    // Restore original implementations
//    Method currentDefaultMethod = class_getInstanceMethod(NSClassFromString(@"XCAXClient_iOS"),
//                                                          @selector(defaultParameters));
//    method_setImplementation(currentDefaultMethod, (IMP)original_defaultParameters);
//    
//    Method currentSnapshotMethod = class_getInstanceMethod(NSClassFromString(@"XCTElementQuery"),
//                                                           NSSelectorFromString(@"snapshotParameters"));
//    method_setImplementation(currentSnapshotMethod, (IMP)original_snapshotParameters);
    
    // Reset our state
    swizzlingPerformed = NO;
    
    // Log for debugging
    id axClient = [[NSClassFromString(@"XCAXClient_iOS") alloc] init];
    NSLog(@"After reset: %@", [axClient defaultParameters]);
}


@implementation XCAXClient_iOS (FBSnapshotReqParams)

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wobjc-load-method"
#pragma clang diagnostic ignored "-Wcast-function-type-strict"

+ (void)load {
    // You can optionally trigger the initial swizzling here
    // FBPerformSnapshotSwizzlingIfNeeded();
    
    // Or set initial values
    // FBDisableHonorModalViews();
}

#pragma clang diagnostic pop

@end
