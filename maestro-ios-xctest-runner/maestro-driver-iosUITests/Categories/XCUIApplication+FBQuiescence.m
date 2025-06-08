/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import "XCUIApplication+FBQuiescence.h"

#import "XCUIApplicationImpl.h"
#import "XCUIApplicationProcess.h"
#import "XCUIApplicationProcess+FBQuiescence.h"


@implementation XCUIApplication (FBQuiescence)

- (BOOL)fb_shouldWaitForQuiescence
{
  return [[self applicationImpl] currentProcess].fb_shouldWaitForQuiescence.boolValue;
}

- (void)setFb_shouldWaitForQuiescence:(BOOL)value
{
  [[self applicationImpl] currentProcess].fb_shouldWaitForQuiescence = @(value);
}

@end
