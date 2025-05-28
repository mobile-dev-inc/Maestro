/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <XCTest/XCTest.h>
@class UTType;

NS_ASSUME_NONNULL_BEGIN

@interface FBScreenshot : NSObject

/**
 Retrieves non-scaled screenshot of the whole screen

 @param quality The number in range 0-3, where 0 is PNG (lossless), 3 is HEIC (lossless), 1- low quality JPEG and 2 - high quality JPEG
 @param error If there is an error, upon return contains an NSError object that describes the problem.
 @return Device screenshot as PNG-encoded data or nil in case of failure
 */
+ (nullable NSData *)takeInOriginalResolutionWithQuality:(NSUInteger)quality
                                                   error:(NSError **)error;

/**
 Retrieves non-scaled screenshot of the whole screen

 @param screenID The screen identifier to take the screenshot from
 @param compressionQuality Normalized screenshot quality value in range 0..1, where 1 is the best quality
 @param uti UTType... constant, which defines the type of the returned screenshot image
 @param timeout how much time to allow for the screenshot to be taken
 @param error If there is an error, upon return contains an NSError object that describes the problem.
 @return Device screenshot as PNG-, HEIC- or JPG-encoded data or nil in case of failure
 */
+ (nullable NSData *)takeInOriginalResolutionWithScreenID:(long long)screenID
                                       compressionQuality:(CGFloat)compressionQuality
                                                      uti:(UTType *)uti
                                                  timeout:(NSTimeInterval)timeout
                                                    error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
