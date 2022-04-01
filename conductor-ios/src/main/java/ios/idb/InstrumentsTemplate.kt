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
