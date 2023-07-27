package maestro.cli.util

import maestro.cli.api.ApiClient
import maestro.cli.update.Updates.BASE_API_URL
import maestro.utils.DepthTracker

object ScreenReporter {

    fun reportMaxDepth() {
        val maxDepth = DepthTracker.getMaxDepth()

        if (maxDepth == 0) return

        ApiClient(BASE_API_URL).sendScreenReport(maxDepth = maxDepth)
    }
}