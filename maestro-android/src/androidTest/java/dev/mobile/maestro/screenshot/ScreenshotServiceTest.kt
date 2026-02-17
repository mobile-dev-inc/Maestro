package dev.mobile.maestro.screenshot

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@Ignore("Disabled since this structure of unit test is weird - We should have libs which should be having their own tests")
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

    @Test
    fun takeScreenshotWithRetry_returnsOnFirstSuccess() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var callCount = 0
        try {
            val result = screenshotService.takeScreenshotWithRetry {
                callCount++
                bitmap
            }
            assertSame(bitmap, result)
            assertEquals(1, callCount)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun takeScreenshotWithRetry_retriesOnNull() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var callCount = 0
        try {
            val result = screenshotService.takeScreenshotWithRetry {
                callCount++
                if (callCount < 3) null else bitmap
            }
            assertSame(bitmap, result)
            assertEquals(3, callCount)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun takeScreenshotWithRetry_throwsAfterAllRetriesExhausted() {
        var callCount = 0
        try {
            screenshotService.takeScreenshotWithRetry {
                callCount++
                null
            }
            fail("Expected NullPointerException to be thrown")
        } catch (e: NullPointerException) {
            assertEquals(3, callCount)
        }
    }
}
