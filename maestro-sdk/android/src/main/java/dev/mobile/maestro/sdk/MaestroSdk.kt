package dev.mobile.maestro.sdk

import dev.mobile.maestro.sdk.mockserver.MaestroMockServerSdk

object MaestroSdk {

    internal var projectId: String? = null

    @JvmStatic
    fun init(projectId: String) {
        this.projectId = projectId
    }

    fun mockServer(): MaestroMockServerSdk {
        ensureInitialised()

        return MaestroMockServerSdk()
    }

    private fun ensureInitialised() {
        if (projectId == null) {
            error("Maestro SDK is not initialised. Please call MaestroSdk.init(projectId) first.")
        }
    }

}