package androidx.test.uiautomator

object UiDeviceExt {

    /**
     * Fix for a UiDevice.click() method that discards taps that happen outside of the screen bounds.
     * The issue with the original method is that it was computing screen bounds incorrectly.
     */
    fun UiDevice.clickExt(x: Int, y: Int) {
        interactionController.clickNoSync(
            x, y
        )
    }

}