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

/**
 A Global Logger object that understands log levels
 */
@interface FBLogger : NSObject

/**
 Log to stdout.
 */
+ (void)log:(NSString *)message;
+ (void)logFmt:(NSString *)format, ... NS_FORMAT_FUNCTION(1,2);

/**
 Log to stdout, only if WDA is Verbose
 */
+ (void)verboseLog:(NSString *)message;
+ (void)verboseLogFmt:(NSString *)format, ... NS_FORMAT_FUNCTION(1,2);

@end

NS_ASSUME_NONNULL_END
