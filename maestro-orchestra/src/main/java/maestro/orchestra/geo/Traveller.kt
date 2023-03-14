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

        val latitudeStep = (end.latitude - start.latitude) / steps
        val longitudeStep = (end.longitude - start.longitude) / steps

        for (i in 1..steps) {
            val latitude = start.latitude + (latitudeStep * i)
            val longitude = start.longitude + (longitudeStep * i)

            maestro.setLocation(latitude, longitude)
            Thread.sleep(timeToSleep)
        }
    }

}