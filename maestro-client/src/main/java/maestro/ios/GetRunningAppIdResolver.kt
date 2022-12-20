package maestro.ios

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ios.api.XCTestDriverClient
import ios.xcrun.Simctl
import maestro.debuglog.DebugLogStore

object GetRunningAppIdResolver {
    private val logger = DebugLogStore.loggerFor(GetRunningAppIdResolver::class.java)
    private val mapper = jacksonObjectMapper()

    fun getRunningAppId(): String? {
        val appIds = Simctl.listApps()
        logger.info("installed apps: $appIds")

        return XCTestDriverClient.runningAppId(appIds).use { response ->
            val runningAppBundleId = if (response.isSuccessful) {
                response.body?.let { body ->
                    val responseBody: GetRunningAppIdResponse = mapper.readValue(
                        String(body.bytes()),
                        GetRunningAppIdResponse::class.java
                    )

                    responseBody.runningAppBundleId
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