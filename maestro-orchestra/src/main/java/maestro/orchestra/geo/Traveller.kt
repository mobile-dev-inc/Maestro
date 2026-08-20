package maestro.orchestra.geo

import kotlinx.coroutines.delay
import maestro.orchestra.TravelCommand
import maestro.orchestra.devicecore.DeviceGateway
import java.util.LinkedList

object Traveller {

    suspend fun travel(
        driver: DeviceGateway,
        points: List<TravelCommand.GeoPoint>,
        speedMPS: Double,
    ) {
        if (points.isEmpty()) {
            return
        }

        val pointsQueue = LinkedList(points)

        var start = pointsQueue.poll()
        driver.setLocation(start.latitude, start.longitude)

        do {
            val next = pointsQueue.poll() ?: return

            travel(driver, start, next, speedMPS)
            start = next
        } while (pointsQueue.isNotEmpty())
    }

    private suspend fun travel(
        driver: DeviceGateway,
        start: TravelCommand.GeoPoint,
        end: TravelCommand.GeoPoint,
        speedMPS: Double,
    ) {
        val steps = 50

        val distance = start.getDistanceInMeters(end)

        val timeToTravel = distance / speedMPS
        val timeToTravelInMilliseconds = (timeToTravel * 1000).toLong()

        val timeToSleep = timeToTravelInMilliseconds / steps

        val sLat = start.latitude.toDouble()
        val sLon = start.longitude.toDouble()

        val eLat = end.latitude.toDouble()
        val eLon = end.longitude.toDouble()

        val latitudeStep = (eLat - sLat) / steps
        val longitudeStep = (eLon - sLon) / steps

        for (i in 1..steps) {
            val latitude = sLat + (latitudeStep * i)
            val longitude = sLon + (longitudeStep * i)

            driver.setLocation(latitude.toString(), longitude.toString())
            delay(timeToSleep)
        }
    }

}
