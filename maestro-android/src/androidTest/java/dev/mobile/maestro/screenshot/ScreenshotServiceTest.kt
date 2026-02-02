package dev.mobile.maestro.screenshot

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotServiceTest {
    private val screenshotService = ScreenshotService()

    @Test
    fun encodePng_withValidBitmap_returnsBytes() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val result = screenshotService.encodePng(bitmap)
            assertNotNull(result)
            assertTrue("Encoded bytes should not be empty", result.size() > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encodePng_withRecycledBitmap_throwsScreenshotException() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.recycle()

        try {
            screenshotService.encodePng(bitmap)
            fail("Expected ScreenshotException to be thrown")
        } catch (e: ScreenshotException) {
            assertTrue(
                "Message should mention recycled bitmap",
                e.message?.contains("recycled") == true
            )
        }
    }

    @Test
    fun encode_withInvalidQualityTooHigh_throwsIllegalArgumentException() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            screenshotService.encode(bitmap, Bitmap.CompressFormat.PNG, quality = 101)
            fail("Expected IllegalArgumentException to be thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Message should mention quality",
                e.message?.contains("quality") == true
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encode_withInvalidQualityNegative_throwsIllegalArgumentException() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            screenshotService.encode(bitmap, Bitmap.CompressFormat.PNG, quality = -1)
            fail("Expected IllegalArgumentException to be thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Message should mention quality",
                e.message?.contains("quality") == true
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encode_withJpegFormat_returnsBytes() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val result = screenshotService.encode(bitmap, Bitmap.CompressFormat.JPEG, quality = 80)
            assertNotNull(result)
            assertTrue("Encoded bytes should not be empty", result.size() > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encode_withWebpFormat_returnsBytes() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            @Suppress("DEPRECATION")
            val result = screenshotService.encode(bitmap, Bitmap.CompressFormat.WEBP, quality = 80)
            assertNotNull(result)
            assertTrue("Encoded bytes should not be empty", result.size() > 0)
        } finally {
            bitmap.recycle()
        }
    }
}
