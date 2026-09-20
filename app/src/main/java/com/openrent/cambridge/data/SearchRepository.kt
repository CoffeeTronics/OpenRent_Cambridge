package com.openrent.cambridge.data

import android.util.Log
import com.openrent.cambridge.model.CommuteSettings
import com.openrent.cambridge.model.Listing
import com.openrent.cambridge.model.ScoredListing
import com.openrent.cambridge.model.SearchCriteria
import com.openrent.cambridge.model.Stop
import com.openrent.cambridge.model.TransitData
import kotlinx.coroutines.delay

/**
 * Ties the pieces together: which areas to search, fetching them, filtering on
 * the real numbers, and costing the commute for everything that survives.
 */
class SearchRepository(
    private val client: OpenRentClient = OpenRentClient()
) {

    companion object {
        private const val TAG = "SearchRepository"

        /** Politeness gap between area requests. */
        private const val AREA_DELAY_MS = 1_200L

        /** Only the best results get a second request to fetch their titles. */
        const val HYDRATE_LIMIT = 60
    }

    /**
     * Every filter is applied here against the real values from the page data,
     * because OpenRent honours its own URL parameters only loosely.
     */
    fun matches(l: Listing, c: SearchCriteria): Boolean {
        // Let agreed means off the market; DSS/LHA listings are excluded by choice.
        if (l.letAgreed || l.dssCovered || l.pricePcm <= 0) return false
        if (c.minPrice != null && l.pricePcm < c.minPrice) return false
        if (c.maxPrice != null && l.pricePcm > c.maxPrice) return false
        if (c.minBeds != null && l.bedrooms < c.minBeds) return false
        if (c.maxBeds != null && l.bedrooms > c.maxBeds) return false
        if (c.excludeShared && l.isShared) return false
        if (c.excludeStudentOnly && l.isStudentOnly) return false
        if (!l.satisfies(c.furnishing)) return false
        if (c.requireBillsIncluded && !l.billsIncluded) return false
        // A day of slack: the reference date is a little fuzzy, and showing a
        // borderline property beats hiding one that would have worked.
        if (c.moveInBeforeDays != null && l.availableFromDays > c.moveInBeforeDays + 1) return false
        // minimumTenancyMonths of 0 means the landlord states no minimum.
        if (c.maxMinimumTenancyMonths != null &&
            l.minimumTenancyMonths > c.maxMinimumTenancyMonths
        ) return false
        return true
    }

    /** One search area, possibly shared by several stops. */
    data class Area(val term: String, val slug: String)

    fun areasFor(stops: List<Stop>): List<Area> =
        stops.map { Area(it.searchTerm, it.searchSlug) }.distinct()

    suspend fun search(
        data: TransitData,
        settings: CommuteSettings,
        criteria: SearchCriteria,
        onProgress: (String) -> Unit = {}
    ): List<ScoredListing> {
        val viable = CommuteCalculator.viableStops(data, settings)
        if (viable.isEmpty()) return emptyList()

        val areas = areasFor(viable)
        val raw = LinkedHashMap<Long, Listing>()

        areas.forEachIndexed { i, area ->
            onProgress("Searching ${area.term} (${i + 1}/${areas.size})...")
            try {
                client.search(area.term, area.slug, criteria)
                    .forEach { raw.putIfAbsent(it.id, it) }
            } catch (e: Exception) {
                Log.e(TAG, "area failed: ${area.term}", e)
            }
            if (i < areas.lastIndex) delay(AREA_DELAY_MS)
        }
        onProgress("Filtering ${raw.size} properties...")

        // OpenRent honours the URL filters only loosely, so re-apply them here
        // against the real values from the page data. Let-agreed properties are
        // always dropped: they are off the market, and in some areas they are the
        // overwhelming majority of what a search returns.
        val matching = raw.values.filter { l -> matches(l, criteria) }
        Log.d(TAG, "${raw.size} found, ${raw.values.count { it.letAgreed }} let agreed, " +
            "${matching.size} available and matching")

        onProgress("Calculating commutes...")
        val scored = matching.mapNotNull { listing ->
            CommuteCalculator.bestCommute(listing, viable, settings, data)
                ?.takeIf { it.totalMin <= settings.maxCommuteMin }
                ?.let { ScoredListing(listing, it) }
        }.sortedBy { it.commute.totalMin }

        if (scored.isEmpty()) return emptyList()

        val top = scored.take(HYDRATE_LIMIT)
        onProgress("Loading details for ${top.size} properties...")
        val hydrated = client.hydrate(top.map { it.listing }).associateBy { it.id }

        return top.map { s -> hydrated[s.listing.id]?.let { s.copy(listing = it) } ?: s }
    }
}
