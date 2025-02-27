#import "XCUIApplicationProcess+FBQuiescence.h"

#import <objc/runtime.h>

#import "FBConfiguration.h"
#import "FBLogger.h"

static void (*original_waitForQuiescenceIncludingAnimationsIdle)(id, SEL, BOOL);
static void (*original_waitForQuiescenceIncludingAnimationsIdlePreEvent)(id, SEL, BOOL, BOOL);

static void swizzledWaitForQuiescenceIncludingAnimationsIdle(id self, SEL _cmd, BOOL includingAnimations)
{
  NSString *bundleId = [self bundleID];
  if (![[self fb_shouldWaitForQuiescence] boolValue] || FBConfiguration.waitForIdleTimeout < DBL_EPSILON) {
    [FBLogger logFmt:@"Quiescence checks are disabled for %@ application. Making it to believe it is idling",
     bundleId];
    return;
  }

  NSTimeInterval desiredTimeout = FBConfiguration.waitForIdleTimeout;
  NSTimeInterval previousTimeout = _XCTApplicationStateTimeout();
  _XCTSetApplicationStateTimeout(desiredTimeout);
  [FBLogger logFmt:@"Waiting up to %@s until %@ is in idle state (%@ animations)",
   @(desiredTimeout), bundleId, includingAnimations ? @"including" : @"excluding"];
  @try {
    original_waitForQuiescenceIncludingAnimationsIdle(self, _cmd, includingAnimations);
  } @finally {
    _XCTSetApplicationStateTimeout(previousTimeout);
  }
}

static void swizzledWaitForQuiescenceIncludingAnimationsIdlePreEvent(id self, SEL _cmd, BOOL includingAnimations, BOOL isPreEvent)
{
  NSString *bundleId = [self bundleID];
  if (![[self fb_shouldWaitForQuiescence] boolValue] || FBConfiguration.waitForIdleTimeout < DBL_EPSILON) {
    [FBLogger logFmt:@"Quiescence checks are disabled for %@ application. Making it to believe it is idling",
     bundleId];
    return;
  }

  NSTimeInterval desiredTimeout = FBConfiguration.waitForIdleTimeout;
  NSTimeInterval previousTimeout = _XCTApplicationStateTimeout();
  _XCTSetApplicationStateTimeout(desiredTimeout);
  [FBLogger logFmt:@"Waiting up to %@s until %@ is in idle state (%@ animations)",
   @(desiredTimeout), bundleId, includingAnimations ? @"including" : @"excluding"];
  @try {
    original_waitForQuiescenceIncludingAnimationsIdlePreEvent(self, _cmd, includingAnimations, isPreEvent);
  } @finally {
    _XCTSetApplicationStateTimeout(previousTimeout);
  }
}

@implementation XCUIApplicationProcess (FBQuiescence)

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wobjc-load-method"
#pragma clang diagnostic ignored "-Wcast-function-type-strict"

+ (void)load
{
  Method waitForQuiescenceIncludingAnimationsIdleMethod = class_getInstanceMethod(self.class, @selector(waitForQuiescenceIncludingAnimationsIdle:));
  Method waitForQuiescenceIncludingAnimationsIdlePreEventMethod = class_getInstanceMethod(self.class, @selector(waitForQuiescenceIncludingAnimationsIdle:isPreEvent:));
  if (nil != waitForQuiescenceIncludingAnimationsIdleMethod) {
    IMP swizzledImp = (IMP)swizzledWaitForQuiescenceIncludingAnimationsIdle;
    original_waitForQuiescenceIncludingAnimationsIdle = (void (*)(id, SEL, BOOL)) method_setImplementation(waitForQuiescenceIncludingAnimationsIdleMethod, swizzledImp);
  } else if (nil != waitForQuiescenceIncludingAnimationsIdlePreEventMethod) {
    IMP swizzledImp = (IMP)swizzledWaitForQuiescenceIncludingAnimationsIdlePreEvent;
    original_waitForQuiescenceIncludingAnimationsIdlePreEvent = (void (*)(id, SEL, BOOL, BOOL)) method_setImplementation(waitForQuiescenceIncludingAnimationsIdlePreEventMethod, swizzledImp);
  } else {
    [FBLogger log:@"Could not find method -[XCUIApplicationProcess waitForQuiescenceIncludingAnimationsIdle:]"];
  }
}

#pragma clang diagnostic pop

static char XCUIAPPLICATIONPROCESS_SHOULD_WAIT_FOR_QUIESCENCE;

@dynamic fb_shouldWaitForQuiescence;

- (NSNumber *)fb_shouldWaitForQuiescence
{
  id result = objc_getAssociatedObject(self, &XCUIAPPLICATIONPROCESS_SHOULD_WAIT_FOR_QUIESCENCE);
  if (nil == result) {
    return @(YES);
  }
  return (NSNumber *)result;
}

- (void)setFb_shouldWaitForQuiescence:(NSNumber *)value
{
  objc_setAssociatedObject(self, &XCUIAPPLICATIONPROCESS_SHOULD_WAIT_FOR_QUIESCENCE, value, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (void)fb_waitForQuiescenceIncludingAnimationsIdle:(bool)waitForAnimations
{
  if ([self respondsToSelector:@selector(waitForQuiescenceIncludingAnimationsIdle:)]) {
    [self waitForQuiescenceIncludingAnimationsIdle:waitForAnimations];
  } else if ([self respondsToSelector:@selector(waitForQuiescenceIncludingAnimationsIdle:isPreEvent:)]) {
    [self waitForQuiescenceIncludingAnimationsIdle:waitForAnimations isPreEvent:NO];
  } else {
      @throw [NSException exceptionWithName: @"NoApiFound" reason:@"The current driver build is not compatible to your device OS version" userInfo:@{}];
  }
}


@end
