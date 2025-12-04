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
            val storage = StorageOptions.getDefaultInstance().service
            val blobId = BlobId.of(bucketName, objectName)
            val blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(getContentType(file))
                .build()

            storage.createFrom(blobInfo, file.toPath())

            val url = "https://storage.googleapis.com/$bucketName/$objectName"
            logger.info("Uploaded ${file.name} to $url")
            url
        } catch (e: Exception) {
            logger.error("Failed to upload file to GCS: ${e.message}", e)
            null
        }
    }

    /**
     * Uploads a recording file to GCS with a structured path.
     *
     * @param file The recording file to upload
     * @param flowName The name of the flow
     * @param shardIndex Optional shard index for sharded runs
     * @param bucketName The GCS bucket name
     * @return The public URL of the uploaded file, or null if upload failed
     */
    fun uploadRecording(
        file: File,
        flowName: String,
        shardIndex: Int? = null,
        bucketName: String? = System.getenv("GCS_BUCKET")
    ): String? {
        val timestamp = System.currentTimeMillis()
        val shardSuffix = shardIndex?.let { "_shard${it + 1}" } ?: ""
        val objectName = "recordings/${timestamp}/${flowName}${shardSuffix}.mp4"
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
