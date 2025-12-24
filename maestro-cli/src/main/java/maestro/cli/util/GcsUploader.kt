package maestro.cli.util

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Utility class for uploading files to Google Cloud Storage.
 *
 * Authentication is handled via the GOOGLE_APPLICATION_CREDENTIALS environment variable,
 * which should point to a service account JSON key file.
 *
 * The bucket name is configured via the GCS_BUCKET environment variable.
 */
object GcsUploader {

    private val logger = LoggerFactory.getLogger(GcsUploader::class.java)

    /**
     * Uploads a file to Google Cloud Storage.
     *
     * @param file The file to upload
     * @param objectName The name/path of the object in the bucket (e.g., "recordings/flow-name.mp4")
     * @param bucketName The GCS bucket name (defaults to GCS_BUCKET env var)
     * @return The public URL of the uploaded file, or null if upload failed
     */
    fun uploadFile(
        file: File,
        objectName: String,
        bucketName: String? = System.getenv("GCS_BUCKET")
    ): String? {
        if (bucketName.isNullOrBlank()) {
            logger.debug("GCS_BUCKET environment variable not set, skipping upload")
            return null
        }

        if (!file.exists()) {
            logger.warn("File does not exist: ${file.absolutePath}")
            return null
        }

        return try {
            logger.info("[GCS-DEBUG] Creating GCS storage client...")
            val storage = StorageOptions.getDefaultInstance().service
            logger.info("[GCS-DEBUG] Storage client created successfully")

            val blobId = BlobId.of(bucketName, objectName)
            val contentType = getContentType(file)
            logger.info("[GCS-DEBUG] BlobId created: bucket=$bucketName, object=$objectName, contentType=$contentType")

            val blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build()

            logger.info("[GCS-DEBUG] Starting upload...")
            storage.createFrom(blobInfo, file.toPath())
            logger.info("[GCS-DEBUG] Upload completed successfully")

            val url = "https://storage.googleapis.com/$bucketName/$objectName"
            logger.info("Uploaded ${file.name} to $url")
            url
        } catch (e: Exception) {
            logger.error("[GCS-DEBUG] Failed to upload file to GCS: ${e.message}", e)
            logger.error("Failed to upload file to GCS: ${e.message}", e)
            null
        }
    }

    /**
     * Uploads a recording file to GCS bucket root.
     *
     * Naming convention: {buildName}-{buildNumber}-{deviceName}-{attemptNumber}-{flowName}.mp4
     * Example: nightly-12345-OmioIOS1-1-login_flow.mp4
     *
     * @param file The recording file to upload
     * @param flowName The name of the flow
     * @param shardIndex Optional shard index for sharded runs (deprecated, use DEVICE_NAME env var instead)
     * @param bucketName The GCS bucket name
     * @return The public URL of the uploaded file, or null if upload failed
     */
    fun uploadRecording(
        file: File,
        flowName: String,
        shardIndex: Int? = null,
        bucketName: String? = System.getenv("GCS_BUCKET")
    ): String? {
        // Read build info from environment variables (set by pipeline)
        val buildName = System.getenv("BUILD_NAME") ?: "local"
        val buildNumber = System.getenv("BUILD_NUMBER") ?: "0"
        val deviceName = System.getenv("DEVICE_NAME") ?: shardIndex?.let { "shard${it + 1}" } ?: "unknown"

        // DEBUG LOGS: Environment variables for naming
        logger.info("[GCS-DEBUG] uploadRecording called for flowName=$flowName")
        logger.info("[GCS-DEBUG] Environment variables:")
        logger.info("[GCS-DEBUG]   BUILD_NAME env=${System.getenv("BUILD_NAME")} -> buildName=$buildName")
        logger.info("[GCS-DEBUG]   BUILD_NUMBER env=${System.getenv("BUILD_NUMBER")} -> buildNumber=$buildNumber")
        logger.info("[GCS-DEBUG]   DEVICE_NAME env=${System.getenv("DEVICE_NAME")} -> deviceName=$deviceName (shardIndex=$shardIndex)")
        logger.info("[GCS-DEBUG]   GCS_BUCKET env=${System.getenv("GCS_BUCKET")} -> bucketName=$bucketName")
        logger.info("[GCS-DEBUG]   GOOGLE_APPLICATION_CREDENTIALS=${System.getenv("GOOGLE_APPLICATION_CREDENTIALS") ?: "NOT SET"}")

        // Naming: BuildName-BuildNumber-DeviceName-FlowName.mp4
        // (No attempt number since we only record on last attempt)
        // Uploaded to root of bucket (no folder structure)
        val objectName = "${buildName}-${buildNumber}-${deviceName}-${flowName}.mp4"

        logger.info("[GCS-DEBUG] Constructed objectName=$objectName")
        logger.info("[GCS-DEBUG] File to upload: ${file.absolutePath}, exists=${file.exists()}, size=${if (file.exists()) file.length() else "N/A"} bytes")

        return uploadFile(file, objectName, bucketName)
    }

    private fun getContentType(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    /**
     * Checks if GCS upload is configured (bucket name is set).
     */
    fun isConfigured(bucketName: String? = System.getenv("GCS_BUCKET")): Boolean {
        return !bucketName.isNullOrBlank()
    }
}
