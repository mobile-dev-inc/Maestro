/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import "XCUIDevice+Helper.h"
#import "FBScreenshot.h"

#include <notify.h>
#import <objc/runtime.h>

#import "XCUIDevice.h"

static const NSTimeInterval FBHomeButtonCoolOffTime = 1.;
static const NSTimeInterval FBScreenLockTimeout = 5.;

@implementation XCUIDevice (Helper)

static bool fb_isLocked;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wobjc-load-method"


- (NSData *)fb_screenshotWithError
{
    NSInteger quality = 3;
  return [FBScreenshot takeInOriginalResolutionWithQuality:quality error:nil];
}

@end
