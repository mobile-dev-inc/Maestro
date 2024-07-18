package maestro.cli.util

import maestro.cli.api.ApiClient
import maestro.utils.DepthTracker

object ScreenReporter {

    fun reportMaxDepth() {
        val maxDepth = DepthTracker.getMaxDepth()

        if (maxDepth == 0) return

        ApiClient(EnvUtils.BASE_API_URL).sendScreenReport(maxDepth = maxDepth)
    }
}
