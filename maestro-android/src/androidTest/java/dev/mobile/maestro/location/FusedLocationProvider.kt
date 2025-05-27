package dev.mobile.maestro.location

import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient

class FusedLocationProvider(
    private val fusedLocationProviderClient: FusedLocationProviderClient
): MockLocationProvider {

    companion object {
        private const val PROVIDER_NAME = "fused"
        private val TAG = FusedLocationProvider::class.java.name
    }

    override fun setLocation(location: Location) {
        fusedLocationProviderClient.setMockLocation(location)
    }

    override fun enable() {
        fusedLocationProviderClient.setMockMode(true)
    }

    override fun disable() {
        fusedLocationProviderClient.setMockMode(false)
    }

    override fun getProviderName(): String {
        return PROVIDER_NAME
    }
}