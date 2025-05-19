package dev.mobile.maestro.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.content.PermissionChecker
import com.google.android.gms.location.FusedLocationProviderClient

class FusedLocationProvider(
    private val context: Context,
    private val fusedLocationProviderClient: FusedLocationProviderClient
): MockLocationProvider {

    companion object {
        private const val PROVIDER_NAME = "fused"
        private val TAG = FusedLocationProvider::class.java.name
    }

    override fun setLocation(location: Location) {
        if (!hasPermissions()) {
            return
        }
        fusedLocationProviderClient.setMockLocation(location)
    }

    override fun enable() {
        if (!hasPermissions()) {
            return
        }
        fusedLocationProviderClient.setMockMode(true)
    }

    override fun disable() {
        if (!hasPermissions()) {
            fusedLocationProviderClient.setMockMode(false)
        }
    }

    override fun getProviderName(): String {
        return PROVIDER_NAME
    }

    private fun hasPermissions(): Boolean {
        if (PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                String.format("Missing permission: '%s'", Manifest.permission.ACCESS_FINE_LOCATION)
            )
            return false
        }
        return true
    }
}