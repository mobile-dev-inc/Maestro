package maestro.cli.util

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Utility class for uploading files to Google Cloud Storage using gcloud CLI.
 *
 * Authentication is handled via gcloud CLI's default credentials:
 * - Application Default Credentials (gcloud auth application-default login)
 * - Service account (gcloud auth activate-service-account)
 * - Workload Identity (on GCP)
 *
 * The bucket name is configured via the GCS_BUCKET environment variable.
 */
object GcsUploader {

    private val logger = LoggerFactory.getLogger(GcsUploader::class.java)

    /**
     * Uploads a file to Google Cloud Storage using gcloud CLI.
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

        val gcsPath = "gs://$bucketName/$objectName"
        val command = listOf("gcloud", "storage", "cp", file.absolutePath, gcsPath)

        logger.info("[GCS-DEBUG] Executing: ${command.joinToString(" ")}")

        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor(120, TimeUnit.SECONDS)

            if (!exitCode) {
                process.destroyForcibly()
                logger.error("[GCS-DEBUG] gcloud command timed out after 120 seconds")
                return null
            }

            if (process.exitValue() == 0) {
                // Use authenticated URL (requires Google login, but works with private buckets)
                val url = "https://storage.cloud.google.com/$bucketName/$objectName"
                logger.info("[GCS-DEBUG] Upload completed successfully")
                logger.info("Uploaded ${file.name} to $url")
                url
            } else {
                logger.error("[GCS-DEBUG] gcloud command failed with exit code ${process.exitValue()}")
                logger.error("[GCS-DEBUG] Output: $output")
                null
            }
        } catch (e: Exception) {
            logger.error("[GCS-DEBUG] Failed to upload file to GCS: ${e.message}", e)
            null
        }
    }

    /**
     * Uploads a recording file to GCS bucket root.
     *
     * Naming convention: {buildName}-{buildNumber}-{deviceName}-{flowName}.mp4
     * Example: nightly-12345-OmioIOS1-login_flow.mp4
     *
     * @param file The recording file to upload
     * @param flowName The name of the flow
     * @param buildName Build identifier (e.g., "nightly", "pr-check")
     * @param buildNumber CI build number
     * @param deviceName Device/simulator name
     * @param bucketName The GCS bucket name
     * @return The public URL of the uploaded file, or null if upload failed
     */
    fun uploadRecording(
        file: File,
        flowName: String,
        buildName: String,
        buildNumber: String,
        deviceName: String,
        bucketName: String? = System.getenv("GCS_BUCKET")
    ): String? {
        // DEBUG LOGS: Environment variables for naming
        logger.info("[GCS-DEBUG] uploadRecording called for flowName=$flowName")
        logger.info("[GCS-DEBUG] buildName=$buildName")
        logger.info("[GCS-DEBUG] buildNumber=$buildNumber")
        logger.info("[GCS-DEBUG] deviceName=$deviceName")
        logger.info("[GCS-DEBUG] GCS_BUCKET (from System.getenv) -> bucketName=$bucketName")

        // Naming: BuildName-BuildNumber-DeviceName-FlowName.mp4
        // (No attempt number since we only record on last attempt)
        // Uploaded to root of bucket (no folder structure)
        val objectName = "${buildName}-${buildNumber}-${deviceName}-${flowName}.mp4"

        logger.info("[GCS-DEBUG] Constructed objectName=$objectName")
        logger.info("[GCS-DEBUG] File to upload: ${file.absolutePath}, exists=${file.exists()}, size=${if (file.exists()) file.length() else "N/A"} bytes")

        return uploadFile(file, objectName, bucketName)
    }

    /**
     * Checks if GCS upload is configured (bucket name is set).
     */
    fun isConfigured(bucketName: String? = System.getenv("GCS_BUCKET")): Boolean {
        return !bucketName.isNullOrBlank()
    }
}
