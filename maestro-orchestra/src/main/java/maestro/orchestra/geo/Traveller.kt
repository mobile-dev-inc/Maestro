package maestro.orchestra.geo

import maestro.Maestro
import maestro.orchestra.TravelCommand
import java.util.LinkedList

object Traveller {

    fun travel(
        maestro: Maestro,
        points: List<TravelCommand.GeoPoint>,
        speedMPS: Double,
    ) {
        if (points.isEmpty()) {
            return
        }

        val pointsQueue = LinkedList(points)

        var start = pointsQueue.poll()
        maestro.setLocation(start.latitude, start.longitude)

        do {
            val next = pointsQueue.poll() ?: return

            travel(maestro, start, next, speedMPS)
            start = next
        } while (pointsQueue.isNotEmpty())
    }

    private fun travel(
        maestro: Maestro,
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

            maestro.setLocation(latitude.toString(), longitude.toString())
            Thread.sleep(timeToSleep)
        }
    }

}