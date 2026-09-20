package com.openrent.cambridge.data

import com.openrent.cambridge.model.CommuteResult
import com.openrent.cambridge.model.CommuteSettings
import com.openrent.cambridge.model.Journey
import com.openrent.cambridge.model.Listing
import com.openrent.cambridge.model.Minutes
import com.openrent.cambridge.model.Stop
import com.openrent.cambridge.model.TransitData
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure commute arithmetic. Deliberately free of Android imports (haversine rather
 * than android.location.Location) so it runs in plain JVM unit tests.
 *
 * The model, for a target arrival time T at Cambridge Research Park:
 *
 *   leave home --cycle--> boarding stop --[timetabled journey]--> Waterbeach --last mile--> CRP
 *
 *   mustReachWaterbeachBy = T - lastMile - ALIGHT_BUFFER
 *   journey               = latest journey from the stop arriving by that time
 *   leaveHomeBy           = journey.dep - cycleToStop - BOARD_BUFFER
 *   arriveAtWork          = journey.arrWaterbeach + ALIGHT_BUFFER + lastMile
 *   totalMin              = arriveAtWork - leaveHomeBy      (time actually travelling)
 *   slackMin              = T - arriveAtWork                (how early the service dumps you)
 *
 * Counting slack as commute would have punished good-but-infrequent rail links:
 * an Ely train arriving 08:26 for a 09:00 start is a 21 minute commute that leaves
 * you 27 minutes early, not a 48 minute commute.
 */
object CommuteCalculator {

    /** Crow-flies is optimistic; real cycling follows roads. */
    const val ROAD_DETOUR_FACTOR = 1.3

    /** Metres per minute at roughly 15 km/h. */
    const val CYCLE_METRES_PER_MIN = 250.0

    /** You never step straight from door to platform. */
    const val MIN_ACCESS_MIN = 2

    /** Time on the platform before departure. */
    const val BOARD_BUFFER_MIN = 3

    /** Getting off the train and to your bike / the shuttle stop. */
    const val ALIGHT_BUFFER_MIN = 2

    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    fun cycleMinutes(metres: Double): Int =
        ((metres * ROAD_DETOUR_FACTOR) / CYCLE_METRES_PER_MIN)
            .toInt()
            .coerceAtLeast(MIN_ACCESS_MIN)

    /** The latest journey that still gets you to Waterbeach by [byTime], if any. */
    fun latestJourneyArrivingBy(journeys: List<Journey>, byTime: Minutes): Journey? =
        journeys.filter { it.arrWaterbeach <= byTime }.maxByOrNull { it.arrWaterbeach }

    /**
     * Best-case commute from a stop, ignoring where in its catchment you live.
     * Used to decide which areas are worth searching at all.
     * Returns null when no service reaches CRP in time from this stop.
     */
    fun baselineCommuteMin(stop: Stop, settings: CommuteSettings, data: TransitData): Int? {
        if (stop.isCycleDirect) {
            val m = haversineMetres(stop.lat, stop.lon, data.crpLat, data.crpLon)
            return cycleMinutes(m)
        }
        val lastMile = data.lastMileMin(settings.lastMileMode)
        val deadline = settings.arrivalAtWork - lastMile - ALIGHT_BUFFER_MIN
        val j = latestJourneyArrivingBy(stop.journeys, deadline) ?: return null
        val leaveBy = j.dep - MIN_ACCESS_MIN - BOARD_BUFFER_MIN
        val arriveAtWork = j.arrWaterbeach + ALIGHT_BUFFER_MIN + lastMile
        return arriveAtWork - leaveBy
    }

    /** Stops from which the commute is achievable within the user's limit. */
    fun viableStops(data: TransitData, settings: CommuteSettings): List<Stop> =
        data.stops.filter { stop ->
            val b = baselineCommuteMin(stop, settings, data)
            b != null && b <= settings.maxCommuteMin
        }

    /** The commute for one property via one specific stop, or null if infeasible. */
    fun commuteVia(
        listing: Listing,
        stop: Stop,
        settings: CommuteSettings,
        data: TransitData
    ): CommuteResult? {
        if (stop.isCycleDirect) {
            val metres = haversineMetres(listing.lat, listing.lon, data.crpLat, data.crpLon)
            val total = cycleMinutes(metres)
            return CommuteResult(
                stop = stop,
                journey = null,
                accessMin = total,
                lastMileMin = 0,
                leaveHomeBy = settings.arrivalAtWork - total,
                arriveAtWork = settings.arrivalAtWork,
                totalMin = total,
                slackMin = 0
            )
        }
        val lastMile = data.lastMileMin(settings.lastMileMode)
        val deadline = settings.arrivalAtWork - lastMile - ALIGHT_BUFFER_MIN
        val journey = latestJourneyArrivingBy(stop.journeys, deadline) ?: return null

        val accessMin = cycleMinutes(
            haversineMetres(listing.lat, listing.lon, stop.lat, stop.lon)
        )
        val leaveHomeBy = journey.dep - accessMin - BOARD_BUFFER_MIN
        val arriveAtWork = journey.arrWaterbeach + ALIGHT_BUFFER_MIN + lastMile
        return CommuteResult(
            stop = stop,
            journey = journey,
            accessMin = accessMin,
            lastMileMin = lastMile,
            leaveHomeBy = leaveHomeBy,
            arriveAtWork = arriveAtWork,
            totalMin = arriveAtWork - leaveHomeBy,
            slackMin = settings.arrivalAtWork - arriveAtWork
        )
    }

    /**
     * The best commute for a property across every candidate stop. Cycling direct
     * wins whenever it beats the train, which is what you want near Waterbeach.
     */
    fun bestCommute(
        listing: Listing,
        stops: List<Stop>,
        settings: CommuteSettings,
        data: TransitData
    ): CommuteResult? = stops
        .mapNotNull { commuteVia(listing, it, settings, data) }
        .minByOrNull { it.totalMin }

    /** Straight-line distance in km, for "x km from <stop>" style display. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        haversineMetres(lat1, lon1, lat2, lon2) / 1000.0

    /** Nearest stop by distance, used only for labelling unroutable properties. */
    fun nearestStop(listing: Listing, stops: List<Stop>): Stop? =
        stops.minByOrNull {
            haversineMetres(listing.lat, listing.lon, it.lat, it.lon)
        }

    internal fun minOf(a: Int, b: Int) = min(a, b)
}
