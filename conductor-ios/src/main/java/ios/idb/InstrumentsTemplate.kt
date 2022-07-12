/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package ios.idb

enum class InstrumentsTemplate(val templateName: String) {
    APP_LAUNCH("App Launch"),
    ACTIVITY_MONITOR("Activity Monitor"),
    ALLOCATIONS("Allocations"),
    ANIMATION_HITCHES("Animation Hitches"),
    CPU_COUNTERS("CPU Counters"),
    CPU_PROFILER("CPU Profiler"),
    CORE_DATA("Core Data"),
    FILE_ACTIVITY("File Activity"),
    GAME_PERFORMANCE("Game Performance"),
    LEAKS("Leaks"),
    LOGGING("Logging"),
    METAL_SYSTEM_TRACE("Metal System Trace"),
    NETWORK("Network"),
    SCENE_KIT("SceneKit"),
    SWIFT_UI("SwiftUI"),
    SYSTEM_TRACE("System Trace"),
    TAILSPIN("Tailspin"),
    TIME_PROFILER("Time Profiler"),
    ZOMBIES("Zombies")
}
