package dev.mobile.maestro.location

import android.location.Location
import android.location.LocationManager

class LocationManagerProvider(
    private val locationManager: LocationManager,
    private val name: String,
    private val requiresNetwork: Boolean,
    private val requiresCell: Boolean,
    private val requiresSatellite: Boolean,
    private val hasMonetaryCost: Boolean,
    private val supportsAltitude: Boolean,
    private val supportsSpeed: Boolean,
    private val supportsBearing: Boolean,
    private val powerRequirement: Int,
    private val accuracy: Int
) : MockLocationProvider {

    override fun setLocation(location: Location) {
        locationManager.setTestProviderLocation(name, location)
    }

    override fun enable() {
        locationManager.addTestProvider(
            name,
            requiresNetwork,
            requiresSatellite,
            requiresCell,
            hasMonetaryCost,
            supportsAltitude,
            supportsSpeed,
            supportsBearing,
            powerRequirement,
            accuracy
        )
        locationManager.setTestProviderEnabled(name, true)
    }

    override fun disable() {
        locationManager.setTestProviderEnabled(name, false)
        locationManager.removeTestProvider(name)
    }

    override fun getProviderName(): String {
        return name
    }
}