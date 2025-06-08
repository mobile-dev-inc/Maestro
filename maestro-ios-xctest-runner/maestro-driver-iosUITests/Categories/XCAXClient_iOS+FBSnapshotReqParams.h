/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <XCTest/XCTest.h>

#import "XCAXClient_iOS.h"

NS_ASSUME_NONNULL_BEGIN

extern NSString *const FBSnapshotMaxDepthKey;

void FBSetCustomParameterForElementSnapshot (NSString* name, id value);

id __nullable FBGetCustomParameterForElementSnapshot (NSString *name);

@interface XCAXClient_iOS (FBSnapshotReqParams)

@end

NS_ASSUME_NONNULL_END
