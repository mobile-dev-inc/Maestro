// Runs inside an iOS Simulator (via `xcrun simctl spawn`) and posts the same vendor-defined
// HID event that the rotate buttons in Xcode's Device Hub send. Inside the simulator,
// locationd relays it as the device orientation, which reaches displays that
// XCUIDevice.shared.orientation does not turn (the iPhone Duo's, Xcode 27.1+).
// The event format is private and undocumented, so a new Xcode may change it.
//
//   orient_helper portrait|landscape-left|landscape-right|faceup|facedown
//
// Adapted from https://github.com/artemnovichkov/hinge
// Copyright (c) 2026 Artem Novichkov. Licensed under the MIT License:
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the "Software"), to deal in the Software without restriction,
// including without limitation the rights to use, copy, modify, merge, publish, distribute,
// sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions: The above copyright notice and this
// permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.

#include <CoreFoundation/CoreFoundation.h>
#include <mach/mach_time.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct __IOHIDEvent *IOHIDEventRef;
typedef struct __IOHIDEventSystemClient *IOHIDEventSystemClientRef;

extern IOHIDEventRef IOHIDEventCreateVendorDefinedEvent(CFAllocatorRef allocator, uint64_t timeStamp,
    uint32_t usagePage, uint32_t usage, uint32_t version, const uint8_t *data, CFIndex length, uint32_t options);
extern IOHIDEventSystemClientRef IOHIDEventSystemClientCreateWithType(CFAllocatorRef allocator, int type,
    CFDictionaryRef properties);
extern void IOHIDEventSystemClientDispatchEvent(IOHIDEventSystemClientRef client, IOHIDEventRef event);
extern CFDataRef IOCFSerialize(CFTypeRef object, CFOptionFlags options);

enum {
    kUsagePage = 0xFF61,
    kUsage = 0x5B,
    kClientTypeSimple = 4,
    kSerializeBinary = 1,
};

static const char *const kOrientations[] = {"portrait", "landscape-left", "landscape-right", "faceup", "facedown"};

static int isKnownOrientation(const char *value) {
    for (size_t i = 0; i < sizeof(kOrientations) / sizeof(kOrientations[0]); i++) {
        if (strcmp(value, kOrientations[i]) == 0) return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2 || !isKnownOrientation(argv[1])) {
        fprintf(stderr, "usage: orient_helper portrait|landscape-left|landscape-right|faceup|facedown\n");
        return 64;
    }

    IOHIDEventSystemClientRef client = IOHIDEventSystemClientCreateWithType(NULL, kClientTypeSimple, NULL);
    if (!client) {
        fprintf(stderr, "orient_helper: failed to create HID event system client\n");
        return 1;
    }

    CFStringRef value = CFStringCreateWithCString(NULL, argv[1], kCFStringEncodingUTF8);
    const void *keys[] = {CFSTR("provider"), CFSTR("source"), CFSTR("type"), CFSTR("value")};
    const void *values[] = {CFSTR("com.apple.Virtualization"), CFSTR("orientation-picker-control"), CFSTR("enum"), value};
    CFDictionaryRef payload = CFDictionaryCreate(NULL, keys, values, 4,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef data = IOCFSerialize(payload, kSerializeBinary);
    if (!data) {
        fprintf(stderr, "orient_helper: failed to serialize the orientation event\n");
        return 1;
    }

    IOHIDEventRef event = IOHIDEventCreateVendorDefinedEvent(NULL, mach_absolute_time(), kUsagePage, kUsage, 0,
        CFDataGetBytePtr(data), CFDataGetLength(data), 0);
    if (!event) {
        fprintf(stderr, "orient_helper: failed to create the orientation event\n");
        return 1;
    }
    IOHIDEventSystemClientDispatchEvent(client, event);

    // Give the event system a moment to deliver before the process exits.
    usleep(100000);
    return 0;
}
