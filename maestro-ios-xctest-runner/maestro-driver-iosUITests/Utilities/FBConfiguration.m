/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import "FBConfiguration.h"

#include "TargetConditionals.h"
#import "XCTestConfiguration.h"

static NSTimeInterval FBWaitForIdleTimeout;
static NSTimeInterval FBAnimationCoolOffTimeout;

@implementation FBConfiguration

#pragma mark Public

+ (NSTimeInterval)waitForIdleTimeout
{
  return FBWaitForIdleTimeout;
}

+ (void)setWaitForIdleTimeout:(NSTimeInterval)timeout
{
  FBWaitForIdleTimeout = timeout;
}

+ (NSTimeInterval)animationCoolOffTimeout
{
  return FBAnimationCoolOffTimeout;
}

+ (void)setAnimationCoolOffTimeout:(NSTimeInterval)timeout
{
  FBAnimationCoolOffTimeout = timeout;
}

@end
