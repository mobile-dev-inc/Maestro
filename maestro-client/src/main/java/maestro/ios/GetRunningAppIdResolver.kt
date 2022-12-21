package maestro.ios

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ios.api.XCTestDriverClient
import ios.xcrun.Simctl
import ios.logger.Logger

class GetRunningAppIdResolver(private val logger: Logger) {

    private val mapper = jacksonObjectMapper()

    fun invoke(): String? {
        val appIds = Simctl.listApps()
        logger.info("installed apps: $appIds")

        return XCTestDriverClient.runningAppId(appIds).use { response ->
            val runningAppBundleId = if (response.isSuccessful) {
                response.body?.let { body ->
                    val responseBody: GetRunningAppIdResponse = mapper.readValue(
                        String(body.bytes()),
                        GetRunningAppIdResponse::class.java
                    )
                    val runningAppId = responseBody.runningAppBundleId
                    logger.info("Running app id response received $runningAppId")
                    runningAppId
                }
            } else {
                logger.info("request to resolve running app id failed with exception - ${response.body?.toString()}")

                return null
            }

            logger.info("found running app id $runningAppBundleId")

            runningAppBundleId
        }
    }
}