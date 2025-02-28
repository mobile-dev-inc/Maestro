//
//     Generated by class-dump 3.5 (64 bit).
//
//     class-dump is Copyright (C) 1997-1998, 2000-2001, 2004-2013 by Steve Nygard.
//

#import <Foundation/Foundation.h>

#import <CDStructures.h>

@class XCAXClient_iOS;
@class XCApplicationMonitor;
@class XCUIApplicationImpl;
@protocol XCTestManager_IDEInterface;
@protocol XCTRunnerAutomationSession;

@interface XCUIApplicationProcess : NSObject
{
    NSObject<OS_dispatch_queue> *_queue;
    BOOL _accessibilityActive;
    unsigned long long _applicationState;
    int _processID;
    id _token;
    int _exitCode;
    BOOL _eventLoopHasIdled;
    BOOL _hasReceivedEventLoopHasIdled;
    BOOL _animationsHaveFinished;
    BOOL _hasReceivedAnimationsHaveFinished;
    BOOL _hasExitCode;
    BOOL _hasCrashReport;
    NSString *_bundleID;
    XCUIApplicationImpl *_applicationImplementation;
    id <XCTRunnerAutomationSession> _automationSession;
    id/*XCElementSnapshot*/ _lastSnapshot;
    XCApplicationMonitor *_applicationMonitor;
    XCAXClient_iOS *_AXClient_iOS;
}

+ (BOOL)automaticallyNotifiesObserversForKey:(id)arg1;
@property XCAXClient_iOS *AXClient_iOS; // @synthesize AXClient_iOS=_AXClient_iOS;
// Since Xcode 10
@property(retain) id/*XCElementSnapshot*/ lastSnapshot; // @synthesize lastSnapshot=_lastSnapshot;
@property XCApplicationMonitor *applicationMonitor; // @synthesize applicationMonitor=_applicationMonitor;
@property(retain) id <XCTRunnerAutomationSession> automationSession; // @synthesize automationSession=_automationSession;
@property BOOL hasCrashReport; // @synthesize hasCrashReport=_hasCrashReport;
@property BOOL hasExitCode; // @synthesize hasExitCode=_hasExitCode;
@property BOOL hasReceivedAnimationsHaveFinished;
@property BOOL animationsHaveFinished;
@property BOOL hasReceivedEventLoopHasIdled;
@property BOOL eventLoopHasIdled;
@property int exitCode;
@property(retain) id token;
@property(nonatomic) int processID;
// Since Xcode 10.2
@property(readonly, copy, nonatomic) NSString *bundleID; // @synthesize bundleID=_bundleID;
@property(readonly) BOOL running;
@property XCUIApplicationImpl *applicationImplementation; // @synthesize applicationImplementation=_applicationImplementation;
@property(nonatomic) unsigned long long applicationState;
@property(nonatomic) BOOL accessibilityActive;
@property(readonly, copy) id/*XCAccessibilityElement*/ accessibilityElement;

- (id)init;
- (id)initWithApplicationMonitor:(id)arg1 AXInterface:(id)arg2;

- (void)terminate;
- (void)waitForViewControllerViewDidDisappearWithTimeout:(double)arg1;
- (void)waitForAutomationSession;
// Before Xcode16-beta5
- (void)waitForQuiescenceIncludingAnimationsIdle:(BOOL)arg1;
// Since Xcode16-beta5
- (void)waitForQuiescenceIncludingAnimationsIdle:(BOOL)arg1 isPreEvent:(BOOL)arg2;


- (id)shortDescription;
- (id)_queue_description;

// Gone with iOS 10.3
- (void)waitForQuiescence;

// Since Xcode 10.2
- (void)_notifyWhenAnimationsAreIdle:(void (^)(id, void *))arg1;
- (_Bool)_supportsAnimationsIdleNotifications;
- (void)_notifyWhenMainRunLoopIsIdle:(void (^)(id, void *))arg1;

@end
