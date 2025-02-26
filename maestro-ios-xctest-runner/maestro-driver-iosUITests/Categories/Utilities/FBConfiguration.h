/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN


@interface FBConfiguration : NSObject

/**
 * Set the idling timeout. If the timeout expires then WDA
 * tries to interact with the application even if it is not idling.
 * Setting it to zero disables idling checks.
 * The default timeout is set to 10 seconds.
 *
 * @param timeout The actual timeout value in float seconds
 */
+ (void)setWaitForIdleTimeout:(NSTimeInterval)timeout;
+ (NSTimeInterval)waitForIdleTimeout;

/**
 * Set the idling timeout for different actions, for example events synthesis, rotation change,
 * etc. If the timeout expires then WDA tries to interact with the application even if it is not idling.
 * Setting it to zero disables idling checks.
 * The default timeout is set to 2 seconds.
 *
 * @param timeout The actual timeout value in float seconds
 */
+ (void)setAnimationCoolOffTimeout:(NSTimeInterval)timeout;
+ (NSTimeInterval)animationCoolOffTimeout;

@end

NS_ASSUME_NONNULL_END
