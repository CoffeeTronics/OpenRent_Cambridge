package com.openrent.cambridge

import com.openrent.cambridge.model.CommuteResult
import com.openrent.cambridge.model.Listing
import com.openrent.cambridge.model.ScoredListing
import com.openrent.cambridge.model.SortOption
import com.openrent.cambridge.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sort comparators, exercised directly. They mirror MainViewModel.sortedResults,
 * which cannot be constructed off-device because it needs an Application.
 */
class SortOptionTest {

    private val stop = Stop(
        id = "S", name = "S", areaName = "S", mode = "rail",
        lat = 52.2, lon = 0.1, searchTerm = "t", searchSlug = "s", journeys = emptyList()
    )

    private fun scored(
        id: Long, price: Int, beds: Int, commute: Int, leaveBy: Int,
        listedAt: Long, availableIn: Int
    ) = ScoredListing(
        listing = Listing(
            id = id, lat = 52.2, lon = 0.1, pricePcm = price, bedrooms = beds,
            bathrooms = 1, isShared = false, isStudio = false, listedAtMs = listedAt,
            availableFromDays = availableIn
        ),
        commute = CommuteResult(
            stop = stop, journey = null, accessMin = 0, lastMileMin = 0,
            leaveHomeBy = leaveBy, arriveAtWork = 9 * 60,
            totalMin = commute, slackMin = 0
        )
    )

    // A: quick commute but an early train. B: longer commute, much later train.
    private val a = scored(1, 2000, 2, 20, 7 * 60 + 50, 100L, 60)
    private val b = scored(2, 900, 1, 30, 8 * 60 + 20, 300L, 0)
    private val c = scored(3, 1200, 2, 25, 8 * 60 + 5, 200L, 14)
    private val all = listOf(a, b, c)

    private fun sort(option: SortOption) = when (option) {
        SortOption.COMMUTE -> all.sortedBy { it.commute.totalMin }
        SortOption.LEAVE_LATEST -> all.sortedByDescending { it.commute.leaveHomeBy }
        SortOption.PRICE -> all.sortedBy { it.listing.pricePcm }
        SortOption.PRICE_PER_BED -> all.sortedBy { it.listing.pricePerBedroom }
        SortOption.NEWEST -> all.sortedByDescending { it.listing.listedAtMs }
        SortOption.AVAILABLE_SOON -> all.sortedBy { it.listing.availableFromDays }
    }.map { it.listing.id }

    @Test
    fun `commute sorts shortest journey first`() {
        assertEquals(listOf(1L, 3L, 2L), sort(SortOption.COMMUTE))
    }

    @Test
    fun `lie-in sorts by latest departure, which differs from commute order`() {
        assertEquals(listOf(2L, 3L, 1L), sort(SortOption.LEAVE_LATEST))
        // The whole point: the shortest commute is the worst lie-in here.
        assertTrue(sort(SortOption.COMMUTE) != sort(SortOption.LEAVE_LATEST))
    }

    @Test
    fun `price sorts cheapest first`() {
        assertEquals(listOf(2L, 3L, 1L), sort(SortOption.PRICE))
    }

    @Test
    fun `price per bed ranks a two-bed above a cheaper one-bed`() {
        // c is £1200 for 2 beds (£600), b is £900 for 1 bed (£900),
        // a is £2000 for 2 beds (£1000).
        assertEquals(listOf(3L, 2L, 1L), sort(SortOption.PRICE_PER_BED))
        assertEquals(600, c.listing.pricePerBedroom)
        assertEquals(900, b.listing.pricePerBedroom)
        assertEquals(1000, a.listing.pricePerBedroom)
        // Ranking by total price would put the 1-bed first instead.
        assertTrue(sort(SortOption.PRICE) != sort(SortOption.PRICE_PER_BED))
    }

    @Test
    fun `newest sorts by real listing date, most recent first`() {
        assertEquals(listOf(2L, 3L, 1L), sort(SortOption.NEWEST))
    }

    @Test
    fun `available sorts soonest move-in first`() {
        assertEquals(listOf(2L, 3L, 1L), sort(SortOption.AVAILABLE_SOON))
    }

    @Test
    fun `a studio is priced per bedroom as if it had one`() {
        val studio = scored(4, 1100, 0, 20, 480, 1L, 0)
        assertEquals(1100, studio.listing.pricePerBedroom)
    }

    @Test
    fun `every option has a distinct label and an explanation`() {
        val labels = SortOption.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        assertTrue(SortOption.entries.all { it.explanation.isNotBlank() })
    }
}
