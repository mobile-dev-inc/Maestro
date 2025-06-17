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

void FBSetCustomParameterForElementSnapshot(NSString *name, id value) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
      customRequestParameters = [NSMutableDictionary new];
    });
    customRequestParameters[name] = value;
}

id FBGetCustomParameterForElementSnapshot(NSString *name) {
    return customRequestParameters[name];
}

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

@implementation XCAXClient_iOS (FBSnapshotReqParams)

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wobjc-load-method"
#pragma clang diagnostic ignored "-Wcast-function-type-strict"

+ (void)load {
    // snapshotKeyHonorModalViews to false to make modals and dialogs visible that are invisible otherwise
    NSString *snapshotKeyHonorModalViewsKey = [[NSProcessInfo processInfo] environment][@"snapshotKeyHonorModalViews"];
    NSLog(@"snapshotKeyHonorModalViews configured to value: %@", snapshotKeyHonorModalViewsKey);
    if ([snapshotKeyHonorModalViewsKey isEqualToString:@"false"]) {
        NSLog(@"Disabling snapshotKeyHonorModalViews to make elements behind modals visible");
        FBSetCustomParameterForElementSnapshot(@"snapshotKeyHonorModalViews", @0);

        Method original_defaultParametersMethod =
            class_getInstanceMethod(self.class, @selector(defaultParameters));
        IMP swizzledDefaultParametersImp = (IMP)swizzledDefaultParameters;
        original_defaultParameters = (id(*)(id, SEL))method_setImplementation(
            original_defaultParametersMethod, swizzledDefaultParametersImp);

        Method original_snapshotParametersMethod = class_getInstanceMethod(NSClassFromString(@"XCTElementQuery"), NSSelectorFromString(@"snapshotParameters"));
        IMP swizzledSnapshotParametersImp = (IMP)swizzledSnapshotParameters;
        original_snapshotParameters = (id(*)(id, SEL))method_setImplementation(original_snapshotParametersMethod, swizzledSnapshotParametersImp);
    }
}

#pragma clang diagnostic pop

@end
