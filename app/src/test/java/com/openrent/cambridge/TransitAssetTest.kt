package com.openrent.cambridge

import com.openrent.cambridge.data.CommuteCalculator
import com.openrent.cambridge.data.TransitRepository
import com.openrent.cambridge.model.CommuteSettings
import com.openrent.cambridge.model.LastMileMode
import com.openrent.cambridge.model.parseHhMm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the timetable actually shipped in assets/.
 *
 * The prototype this app replaces shipped Vancouver SkyTrain data in a Cambridge
 * commute app and crashed on first search. These tests exist so that cannot
 * happen again unnoticed.
 */
class TransitAssetTest {

    private val data by lazy {
        val json = javaClass.classLoader!!
            .getResourceAsStream("transit_waterbeach.json")!!
            .bufferedReader().use { it.readText() }
        TransitRepository.parse(json)
    }

    @Test
    fun `asset parses and is not empty`() {
        assertTrue(data.stops.isNotEmpty())
        assertTrue(data.validFrom.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `every stop sits in Cambridgeshire`() {
        data.stops.forEach { s ->
            assertTrue("${s.name} lat ${s.lat} is not in Cambridgeshire", s.lat in 52.0..52.7)
            assertTrue("${s.name} lon ${s.lon} is not in Cambridgeshire", s.lon in -0.4..0.7)
        }
    }

    @Test
    fun `destination is Cambridge Research Park and the interchange is Waterbeach`() {
        assertEquals(52.29, data.crpLat, 0.05)
        assertEquals(0.17, data.crpLon, 0.05)
        assertEquals(52.26, data.waterbeachLat, 0.05)
        assertEquals(0.197, data.waterbeachLon, 0.05)
    }

    @Test
    fun `journeys are chronological and arrive after they depart`() {
        data.stops.forEach { stop ->
            stop.journeys.zipWithNext { a, b ->
                assertTrue("${stop.name} journeys out of order", a.dep <= b.dep)
            }
            stop.journeys.forEach { j ->
                assertTrue(
                    "${stop.name} ${j.dep} -> ${j.arrWaterbeach} does not move forward",
                    j.arrWaterbeach > j.dep
                )
                assertTrue(
                    "${stop.name} journey takes over 2 hours",
                    j.arrWaterbeach - j.dep <= 120
                )
            }
        }
    }

    @Test
    fun `legs within a journey are consistent with it`() {
        data.stops.flatMap { it.journeys }.forEach { j ->
            if (j.legs.isEmpty()) return@forEach
            assertEquals("first leg must start when the journey does", j.dep, j.legs.first().dep)
            assertEquals(
                "last leg must end when the journey reaches Waterbeach",
                j.arrWaterbeach, j.legs.last().arr
            )
            j.legs.zipWithNext { a, b ->
                assertTrue("legs overlap or go backwards", b.dep >= a.arr)
            }
        }
    }

    @Test
    fun `transit stops have a usable timetable and cycle-direct stops have none`() {
        data.stops.forEach { s ->
            if (s.isCycleDirect) {
                assertTrue("${s.name} should have no journeys", s.journeys.isEmpty())
            } else {
                assertTrue("${s.name} has too few journeys to be useful", s.journeys.size >= 2)
            }
        }
    }

    @Test
    fun `every stop names a search area`() {
        data.stops.forEach { s ->
            assertTrue("${s.name} has no search term", s.searchTerm.isNotBlank())
            assertTrue("${s.name} has no search slug", s.searchSlug.isNotBlank())
        }
    }

    @Test
    fun `no duplicate stops survive loading`() {
        val keys = data.stops.map { it.name to it.mode }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `the Fen Line is present with real services`() {
        val ely = data.stops.firstOrNull { it.name.contains("Ely", true) && it.mode == "rail" }
        assertTrue("no Ely rail stop in the asset", ely != null)
        assertTrue("Ely has too few trains", ely!!.journeys.size >= 5)
        // Ely to Waterbeach is a short direct hop on the Fen Line.
        val direct = ely.journeys.filter { it.legs.size == 1 }
        assertTrue("expected direct Ely trains", direct.isNotEmpty())
        assertTrue(direct.all { it.arrWaterbeach - it.dep in 5..25 })
    }

    @Test
    fun `cycle-direct areas are genuinely cyclable to CRP`() {
        data.stops.filter { it.isCycleDirect }.forEach { s ->
            val km = CommuteCalculator.km(s.lat, s.lon, data.crpLat, data.crpLon)
            assertTrue("${s.name} is ${"%.1f".format(km)} km from CRP - too far to cycle", km < 8.0)
        }
    }

    @Test
    fun `a realistic morning commute is achievable from the shipped data`() {
        val settings = CommuteSettings(parseHhMm("09:00")!!, 60, LastMileMode.CYCLE)
        val viable = CommuteCalculator.viableStops(data, settings)
        assertTrue("no area is reachable in 60 min - the asset is unusable", viable.isNotEmpty())
        assertTrue("expected Ely to be reachable within an hour",
            viable.any { it.name.contains("Ely", true) })
    }

    @Test
    fun `a tighter limit selects strictly fewer areas`() {
        val arrive = parseHhMm("09:00")!!
        val wide = CommuteCalculator.viableStops(
            data, CommuteSettings(arrive, 75, LastMileMode.CYCLE)
        )
        val tight = CommuteCalculator.viableStops(
            data, CommuteSettings(arrive, 30, LastMileMode.CYCLE)
        )
        assertTrue("max commute has no effect on area selection", tight.size < wide.size)
    }

    @Test
    fun `the timetable covers a normal morning arrival`() {
        assertTrue(data.coversArrival(parseHhMm("09:00")!!, LastMileMode.CYCLE))
        assertTrue(data.coversArrival(parseHhMm("08:00")!!, LastMileMode.CYCLE))
    }
}
