package com.openrent.cambridge

import com.openrent.cambridge.data.CommuteCalculator
import com.openrent.cambridge.model.CommuteSettings
import com.openrent.cambridge.model.Journey
import com.openrent.cambridge.model.LastMileMode
import com.openrent.cambridge.model.Leg
import com.openrent.cambridge.model.Listing
import com.openrent.cambridge.model.Stop
import com.openrent.cambridge.model.TransitData
import com.openrent.cambridge.model.parseHhMm
import com.openrent.cambridge.model.toHhMm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteCalculatorTest {

    private fun j(dep: String, arr: String) = Journey(
        dep = parseHhMm(dep)!!,
        arrWaterbeach = parseHhMm(arr)!!,
        legs = listOf(
            Leg("rail", "Great Northern", "Ely", "Waterbeach", parseHhMm(dep)!!, parseHhMm(arr)!!)
        )
    )

    private val ely = Stop(
        id = "ELY", name = "Ely", areaName = "Ely", mode = "rail",
        lat = 52.392227, lon = 0.2667025,
        searchTerm = "Ely, Cambridgeshire", searchSlug = "ely-cambridgeshire",
        journeys = listOf(j("07:17", "07:26"), j("07:47", "07:56"), j("08:17", "08:26"))
    )

    private val cycleDirect = Stop(
        id = "CD", name = "Waterbeach village", areaName = "Waterbeach village",
        mode = "cycle_direct", lat = 52.265765, lon = 0.1909734,
        searchTerm = "Waterbeach", searchSlug = "waterbeach", journeys = emptyList()
    )

    private val data = TransitData(
        validFrom = "2026-09-19", serviceDate = "2026-09-21", source = "test",
        crpLat = 52.2928224, crpLon = 0.1702614,
        waterbeachLat = 52.2618613, waterbeachLon = 0.1969942,
        cycleLastMileMin = 7, shuttleLastMileMin = 10,
        stops = listOf(ely, cycleDirect)
    )

    private fun settings(arrive: String = "09:00", max: Int = 60, mode: LastMileMode = LastMileMode.CYCLE) =
        CommuteSettings(parseHhMm(arrive)!!, max, mode)

    private fun listingAt(lat: Double, lon: Double) = Listing(
        id = 1, lat = lat, lon = lon, pricePcm = 1000, bedrooms = 2,
        bathrooms = 1, isShared = false, isStudio = false, listedAtMs = 0
    )

    @Test
    fun `picks the latest train that still arrives in time`() {
        // 09:00 arrival - 7 last mile - 2 alight = must be at Waterbeach by 08:51.
        val picked = CommuteCalculator.latestJourneyArrivingBy(ely.journeys, parseHhMm("08:51")!!)
        assertEquals(parseHhMm("08:17"), picked!!.dep)
    }

    @Test
    fun `picks an earlier train when the arrival deadline is tighter`() {
        val picked = CommuteCalculator.latestJourneyArrivingBy(ely.journeys, parseHhMm("07:56")!!)
        assertEquals(parseHhMm("07:47"), picked!!.dep)
    }

    @Test
    fun `returns null when nothing arrives early enough`() {
        assertNull(CommuteCalculator.latestJourneyArrivingBy(ely.journeys, parseHhMm("06:00")!!))
    }

    @Test
    fun `no feasible journey yields no commute`() {
        // Arriving at 06:30 is before the first train of the day.
        val r = CommuteCalculator.commuteVia(
            listingAt(52.39, 0.26), ely, settings(arrive = "06:30"), data
        )
        assertNull(r)
    }

    @Test
    fun `total commute is travel time, not time until the target arrival`() {
        val r = CommuteCalculator.commuteVia(listingAt(52.39, 0.26), ely, settings(), data)!!
        assertEquals(r.arriveAtWork - r.leaveHomeBy, r.totalMin)
        // Waiting at work because the train was early is reported, not charged.
        assertEquals(settings().arrivalAtWork - r.arriveAtWork, r.slackMin)
        assertTrue(r.totalMin < settings().arrivalAtWork - r.leaveHomeBy + 1)
    }

    @Test
    fun `an early train is not counted as a longer commute`() {
        // Last train in is 08:17 -> 08:26, +2 alight +7 last mile = at work 08:35,
        // 25 minutes before a 09:00 start. The commute is the 23 minutes travelled.
        val r = CommuteCalculator.commuteVia(
            listingAt(52.392227, 0.2667025), ely, settings(), data
        )!!
        assertEquals(parseHhMm("08:35"), r.arriveAtWork)
        assertEquals(25, r.slackMin)
        assertEquals(23, r.totalMin)
    }

    @Test
    fun `commute is built from access, buffers, ride and last mile`() {
        val listing = listingAt(52.392227, 0.2667025) // at the station itself
        val s = settings()
        val r = CommuteCalculator.commuteVia(listing, ely, s, data)!!

        assertEquals(CommuteCalculator.MIN_ACCESS_MIN, r.accessMin)
        assertEquals(7, r.lastMileMin)
        // leave = 08:17 dep - 2 access - 3 board = 08:12
        assertEquals(parseHhMm("08:12"), r.leaveHomeBy)
        assertEquals("08:12", r.leaveHomeBy.toHhMm())
        // 2 access + 3 board + 9 on the train + 2 alight + 7 last mile = 23
        assertEquals(23, r.totalMin)
    }

    @Test
    fun `switching to the shuttle shifts the commute by exactly three minutes`() {
        val listing = listingAt(52.392227, 0.2667025)
        val cycle = CommuteCalculator.commuteVia(listing, ely, settings(mode = LastMileMode.CYCLE), data)!!
        val shuttle = CommuteCalculator.commuteVia(listing, ely, settings(mode = LastMileMode.SHUTTLE), data)!!
        // The shuttle is 3 min slower, so the deadline at Waterbeach is 3 min earlier.
        assertEquals(3, shuttle.lastMileMin - cycle.lastMileMin)
        assertTrue(shuttle.totalMin >= cycle.totalMin)
    }

    @Test
    fun `cycle-direct needs no train and reports no last mile`() {
        val listing = listingAt(52.2928224, 0.1702614) // at CRP
        val r = CommuteCalculator.commuteVia(listing, cycleDirect, settings(), data)!!
        assertNull(r.journey)
        assertTrue(r.isCycleDirect)
        assertEquals(CommuteCalculator.MIN_ACCESS_MIN, r.totalMin)
    }

    @Test
    fun `cycling direct beats the train for a property beside CRP`() {
        val listing = listingAt(52.2928224, 0.1702614)
        val best = CommuteCalculator.bestCommute(listing, data.stops, settings(), data)!!
        assertTrue(best.isCycleDirect)
    }

    @Test
    fun `cycle time floors at the minimum access time`() {
        assertEquals(CommuteCalculator.MIN_ACCESS_MIN, CommuteCalculator.cycleMinutes(0.0))
        assertEquals(CommuteCalculator.MIN_ACCESS_MIN, CommuteCalculator.cycleMinutes(50.0))
    }

    @Test
    fun `cycle time applies the road detour factor`() {
        // 5 km crow-flies -> 6.5 km of road at 250 m/min -> 26 min.
        assertEquals(26, CommuteCalculator.cycleMinutes(5000.0))
    }

    @Test
    fun `haversine matches a known station separation`() {
        // Waterbeach station to Cambridge Research Park is about 3.9 km.
        val m = CommuteCalculator.haversineMetres(52.2618613, 0.1969942, 52.2928224, 0.1702614)
        assertEquals(3894.0, m, 60.0)
    }

    @Test
    fun `a tight limit rules areas out and a generous one lets them in`() {
        val strict = CommuteCalculator.viableStops(data, settings(max = 20))
        val relaxed = CommuteCalculator.viableStops(data, settings(max = 60))
        assertTrue(strict.size < relaxed.size)
        assertTrue(relaxed.contains(ely))
        // Cycling to CRP from Waterbeach village stays within 20 minutes.
        assertTrue(strict.contains(cycleDirect))
    }

    @Test
    fun `time formatting round-trips`() {
        assertEquals("08:05", parseHhMm("08:05")!!.toHhMm())
        assertEquals("00:00", parseHhMm("00:00")!!.toHhMm())
        assertNull(parseHhMm("25:00"))
        assertNull(parseHhMm("garbage"))
    }

    @Test
    fun `best commute across stops is never worse than any single stop`() {
        val listing = listingAt(52.39, 0.265)
        val best = CommuteCalculator.bestCommute(listing, data.stops, settings(), data)
        assertNotNull(best)
        data.stops.forEach { stop ->
            CommuteCalculator.commuteVia(listing, stop, settings(), data)?.let {
                assertTrue(best!!.totalMin <= it.totalMin)
            }
        }
    }
}
