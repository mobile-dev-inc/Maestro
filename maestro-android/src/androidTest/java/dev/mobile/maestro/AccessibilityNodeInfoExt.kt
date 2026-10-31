package dev.mobile.maestro

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeInfoExt {

    /**
     * Retrieves the hint text associated with this [android.view.accessibility.AccessibilityNodeInfo].
     *
     * If the device API level is below 26 (Oreo) or the hint text is null, this function provides a fallback
     * by returning an empty CharSequence instead.
     *
     * @return [CharSequence] representing the hint text or its fallback.
     */
    fun AccessibilityNodeInfo.getHintOrFallback(): CharSequence {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this.hintText != null) {
            this.hintText
        } else {
            ""
        }
    }

    private const val SUPPLEMENTAL_DESCRIPTION_KEY =
        "androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY"

    /**
     * Retrieves the supplemental description of this node, or an empty CharSequence.
     *
     * WebView 150+ delivers a web text input's accessible name here rather than in hintText.
     * API 36 exposes it natively; below that AndroidX stores it in the node's extras.
     */
    fun AccessibilityNodeInfo.getSupplementalDescriptionOrFallback(): CharSequence {
        if (Build.VERSION.SDK_INT >= 36) {
            supplementalDescription?.let { return it }
        }
        return extras?.getCharSequence(SUPPLEMENTAL_DESCRIPTION_KEY) ?: ""
    }

    /**
     * Retrieves the text of this [android.view.accessibility.AccessibilityNodeInfo].
     *
     * On API 26 (Oreo) and above, an empty input showing its hint reports the hint through
     * [AccessibilityNodeInfo.getText]. This function returns an empty CharSequence in that case,
     * so the hint is only reported as hint text and not as text.
     *
     * @return [CharSequence] representing the text or its fallback.
     */
    fun AccessibilityNodeInfo.getTextOrFallback(): CharSequence {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this.isShowingHintText) {
            ""
        } else {
            this.text ?: ""
        }
    }

}
