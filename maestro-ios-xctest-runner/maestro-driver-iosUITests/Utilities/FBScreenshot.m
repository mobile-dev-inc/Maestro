/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import "FBScreenshot.h"
#import "XCTestDaemonsProxy.h"

@import UniformTypeIdentifiers;

#import "XCTestManager_ManagerInterface-Protocol.h"
#import "XCUIScreen.h"

static const NSTimeInterval SCREENSHOT_TIMEOUT = 20.;
static const CGFloat SCREENSHOT_SCALE = 1.0;  // Screenshot API should keep the original screen scale
static const CGFloat HIGH_QUALITY = 0.8;
static const CGFloat LOW_QUALITY = 0.25;

NSString *formatTimeInterval(NSTimeInterval interval) {
  NSUInteger milliseconds = (NSUInteger)(interval * 1000);
  return [NSString stringWithFormat:@"%ld ms", milliseconds];
}

@implementation FBScreenshot

+ (CGFloat)compressionQualityWithQuality:(NSUInteger)quality
{
  switch (quality) {
    case 1:
      return HIGH_QUALITY;
    case 2:
      return LOW_QUALITY;
    default:
      return 1.0;
  }
}

+ (UTType *)imageUtiWithQuality:(NSUInteger)quality
{
  switch (quality) {
    case 1:
    case 2:
      return UTTypeJPEG;
    case 3:
      return UTTypeHEIC;
    default:
      return UTTypePNG;
  }
}

+ (NSData *)takeInOriginalResolutionWithQuality:(NSUInteger)quality
                                          error:(NSError **)error
{
  XCUIScreen *mainScreen = XCUIScreen.mainScreen;
  return [self.class takeWithScreenID:mainScreen.displayID
                                scale:SCREENSHOT_SCALE
                   compressionQuality:[self.class compressionQualityWithQuality:quality]
                            sourceUTI:[self.class imageUtiWithQuality:quality]
                                error:error];
}

+ (NSData *)takeWithScreenID:(long long)screenID
                       scale:(CGFloat)scale
          compressionQuality:(CGFloat)compressionQuality
                   sourceUTI:(UTType *)uti
                       error:(NSError **)error
{
  NSData *screenshotData = [self.class takeInOriginalResolutionWithScreenID:screenID
                                                         compressionQuality:compressionQuality
                                                                        uti:uti
                                                                    timeout:SCREENSHOT_TIMEOUT
                                                                      error:error];
  if (nil == screenshotData) {
    return nil;
  }
    return screenshotData;
}

+ (NSData *)takeInOriginalResolutionWithScreenID:(long long)screenID
                              compressionQuality:(CGFloat)compressionQuality
                                             uti:(UTType *)uti
                                         timeout:(NSTimeInterval)timeout
                                           error:(NSError **)error
{
  id<XCTestManager_ManagerInterface> proxy = [XCTestDaemonsProxy testRunnerProxy];
  __block NSData *screenshotData = nil;
  __block NSError *innerError = nil;
  dispatch_semaphore_t sem = dispatch_semaphore_create(0);
  id screnshotRequest = [self.class screenshotRequestWithScreenID:screenID
                                                             rect:CGRectNull
                                                              uti:uti
                                               compressionQuality:compressionQuality
                                                            error:error];
  if (nil == screnshotRequest) {
    return nil;
  }
  [proxy _XCT_requestScreenshot:screnshotRequest
                      withReply:^(id image, NSError *err) {
    if (nil != err) {
      innerError = err;
    } else {
      screenshotData = [image data];
    }
    dispatch_semaphore_signal(sem);
  }];
  int64_t timeoutNs = (int64_t)(timeout * NSEC_PER_SEC);
  if (0 != dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, timeoutNs))) {
    NSString *timeoutMsg = [NSString stringWithFormat:@"Cannot take a screenshot within %@ timeout", formatTimeInterval(SCREENSHOT_TIMEOUT)];
    if (nil == error) {
        NSLog(timeoutMsg);
    } else if (nil == innerError) {
//      [[[FBErrorBuilder builder]
//        withDescription:timeoutMsg]
//       buildError:error];
    }
  };
  if (nil != error && nil != innerError) {
    *error = innerError;
  }
  return screenshotData;
}

+ (nullable id)imageEncodingWithUniformTypeIdentifier:(UTType *)uti
                                   compressionQuality:(CGFloat)compressionQuality
                                                error:(NSError **)error
{
  Class imageEncodingClass = NSClassFromString(@"XCTImageEncoding");
  if (nil == imageEncodingClass) {
//    [[[FBErrorBuilder builder]
//      withDescription:@"Cannot find XCTImageEncoding class"]
//     buildError:error];
    return nil;
  }

  if ([uti conformsToType:UTTypeHEIC]) {
    static BOOL isHeicSuppported = NO;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
      SEL selector = NSSelectorFromString(@"supportsHEICImageEncoding");
      NSMethodSignature *signature = [imageEncodingClass methodSignatureForSelector:selector];
      if (nil != signature) {
        NSInvocation *invocation = [NSInvocation invocationWithMethodSignature:signature];
        [invocation setSelector:selector];
        [invocation invokeWithTarget:imageEncodingClass];
        [invocation getReturnValue:&isHeicSuppported];
      }
    });
    if (!isHeicSuppported) {
//      [FBLogger logFmt:@"The device under test does not support HEIC image encoding. Falling back to PNG"];
      uti = UTTypePNG;
    }
  }

  id imageEncodingAllocated = [imageEncodingClass alloc];
  SEL imageEncodingConstructorSelector = NSSelectorFromString(@"initWithUniformTypeIdentifier:compressionQuality:");
  if (![imageEncodingAllocated respondsToSelector:imageEncodingConstructorSelector]) {
//    [[[FBErrorBuilder builder]
//      withDescription:@"'initWithUniformTypeIdentifier:compressionQuality:' contructor is not found on XCTImageEncoding class"]
//     buildError:error];
    return nil;
  }
  NSMethodSignature *imageEncodingContructorSignature = [imageEncodingAllocated methodSignatureForSelector:imageEncodingConstructorSelector];
  NSInvocation *imageEncodingInitInvocation = [NSInvocation invocationWithMethodSignature:imageEncodingContructorSignature];
  [imageEncodingInitInvocation setSelector:imageEncodingConstructorSelector];
  NSString *utiIdentifier = uti.identifier;
  [imageEncodingInitInvocation setArgument:&utiIdentifier atIndex:2];
  [imageEncodingInitInvocation setArgument:&compressionQuality atIndex:3];
  [imageEncodingInitInvocation invokeWithTarget:imageEncodingAllocated];
  id __unsafe_unretained imageEncoding;
  [imageEncodingInitInvocation getReturnValue:&imageEncoding];
  return imageEncoding;
}

+ (nullable id)screenshotRequestWithScreenID:(long long)screenID
                                        rect:(struct CGRect)rect
                                         uti:(UTType *)uti
                          compressionQuality:(CGFloat)compressionQuality
                                       error:(NSError **)error
{
  id imageEncoding = [self.class imageEncodingWithUniformTypeIdentifier:uti
                                                     compressionQuality:compressionQuality
                                                                  error:error];
  if (nil == imageEncoding) {
    return nil;
  }

  Class screenshotRequestClass = NSClassFromString(@"XCTScreenshotRequest");
  if (nil == screenshotRequestClass) {
//    [[[FBErrorBuilder builder]
//      withDescription:@"Cannot find XCTScreenshotRequest class"]
//     buildError:error];
    return nil;
  }
  id screenshotRequestAllocated = [screenshotRequestClass alloc];
  SEL screenshotRequestConstructorSelector = NSSelectorFromString(@"initWithScreenID:rect:encoding:");
  if (![screenshotRequestAllocated respondsToSelector:screenshotRequestConstructorSelector]) {
//    [[[FBErrorBuilder builder]
//      withDescription:@"'initWithScreenID:rect:encoding:' contructor is not found on XCTScreenshotRequest class"]
//     buildError:error];
    return nil;
  }
  NSMethodSignature *screenshotRequestContructorSignature = [screenshotRequestAllocated methodSignatureForSelector:screenshotRequestConstructorSelector];
  NSInvocation *screenshotRequestInitInvocation = [NSInvocation invocationWithMethodSignature:screenshotRequestContructorSignature];
  [screenshotRequestInitInvocation setSelector:screenshotRequestConstructorSelector];
  [screenshotRequestInitInvocation setArgument:&screenID atIndex:2];
  [screenshotRequestInitInvocation setArgument:&rect atIndex:3];
  [screenshotRequestInitInvocation setArgument:&imageEncoding atIndex:4];
  [screenshotRequestInitInvocation invokeWithTarget:screenshotRequestAllocated];
  id __unsafe_unretained screenshotRequest;
  [screenshotRequestInitInvocation getReturnValue:&screenshotRequest];
  return screenshotRequest;
}

@end
