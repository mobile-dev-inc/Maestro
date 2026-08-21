package maestro.orchestra

enum class StepScreenshotTiming {
    BEFORE,
    AFTER,
}

data class StepArtifactConfig(
    val screenshotTiming: StepScreenshotTiming? = null,
    val captureHierarchy: Boolean = false,
) {
    init {
        require(!captureHierarchy || screenshotTiming != null) {
            "Step hierarchy capture requires step screenshots so both artifacts describe the same screen."
        }
    }
}
