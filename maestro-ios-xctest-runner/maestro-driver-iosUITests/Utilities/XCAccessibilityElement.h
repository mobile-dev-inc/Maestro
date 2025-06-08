#import <XCTest/XCTest.h>

@protocol XCAccessibilityElement <NSObject>

@property(readonly) id payload; // @synthesize payload=_payload;
@property(readonly) int processIdentifier; // @synthesize processIdentifier=_processIdentifier;
@property(readonly) const struct __AXUIElement *AXUIElement; // @synthesize AXUIElement=_axElement;
@property(readonly, getter=isNative) BOOL native;

+ (id)elementWithAXUIElement:(struct __AXUIElement *)arg1;
+ (id)elementWithProcessIdentifier:(int)arg1;
+ (id)deviceElement;
+ (id)mockElementWithProcessIdentifier:(int)arg1 payload:(id)arg2;
+ (id)mockElementWithProcessIdentifier:(int)arg1;

- (id)initWithMockProcessIdentifier:(int)arg1 payload:(id)arg2;
- (id)initWithAXUIElement:(struct __AXUIElement *)arg1;
- (id)init;

@end
