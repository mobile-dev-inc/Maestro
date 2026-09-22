// Runs inside a foldable iOS Simulator (via `xcrun simctl spawn`) and posts the same
// vendor-defined HID event that the hidden hinge slider in Xcode's Device Hub sends.
// The event format is private and undocumented, so a new Xcode may change it; callers
// verify the result with `xcrun devicectl device motion hinge-angle`.
//
//   hinge_helper <degrees>
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
#include <stdlib.h>
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

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: hinge_helper <degrees>\n");
        return 64;
    }

    double degrees = atof(argv[1]);
    if (degrees < 0) degrees = 0;
    if (degrees > 180) degrees = 180;

    IOHIDEventSystemClientRef client = IOHIDEventSystemClientCreateWithType(NULL, kClientTypeSimple, NULL);
    if (!client) {
        fprintf(stderr, "hinge_helper: failed to create HID event system client\n");
        return 1;
    }

    CFNumberRef value = CFNumberCreate(NULL, kCFNumberDoubleType, &degrees);
    const void *keys[] = {CFSTR("provider"), CFSTR("source"), CFSTR("type"), CFSTR("value")};
    const void *values[] = {CFSTR("com.apple.Virtualization"), CFSTR("hinge-slider-control"), CFSTR("range"), value};
    CFDictionaryRef payload = CFDictionaryCreate(NULL, keys, values, 4,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef data = IOCFSerialize(payload, kSerializeBinary);
    if (!data) {
        fprintf(stderr, "hinge_helper: failed to serialize the hinge event\n");
        return 1;
    }

    IOHIDEventRef event = IOHIDEventCreateVendorDefinedEvent(NULL, mach_absolute_time(), kUsagePage, kUsage, 0,
        CFDataGetBytePtr(data), CFDataGetLength(data), 0);
    if (!event) {
        fprintf(stderr, "hinge_helper: failed to create the hinge event\n");
        return 1;
    }
    IOHIDEventSystemClientDispatchEvent(client, event);

    // Give the event system a moment to deliver before the process exits.
    usleep(100000);
    return 0;
}
