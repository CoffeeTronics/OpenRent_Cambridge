package com.openrent.cambridge.model

/** Minutes since midnight. All time arithmetic in the app uses this form. */
typealias Minutes = Int

fun Minutes.toHhMm(): String = "%02d:%02d".format(this / 60 % 24, this % 60)

fun parseHhMm(s: String): Minutes? {
    val parts = s.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

enum class LastMileMode(val label: String) { CYCLE("Cycle"), SHUTTLE("Shuttle") }

/** One vehicle leg of a journey, kept for display on the result card. */
data class Leg(
    val mode: String,
    val route: String,
    val from: String,
    val to: String,
    val dep: Minutes,
    val arr: Minutes
)

/** A timetabled journey from a boarding stop through to Waterbeach station. */
data class Journey(
    val dep: Minutes,
    val arrWaterbeach: Minutes,
    val legs: List<Leg>
) {
    val transfers: Int get() = (legs.size - 1).coerceAtLeast(0)
}

/**
 * A boarding point. [mode] "cycle_direct" means there is no transit leg at all:
 * you cycle from the property straight to Cambridge Research Park.
 */
data class Stop(
    val id: String,
    val name: String,
    val areaName: String,
    val mode: String,
    val lat: Double,
    val lon: Double,
    val searchTerm: String,
    val searchSlug: String,
    val journeys: List<Journey>
) {
    val isCycleDirect: Boolean get() = mode == "cycle_direct"
}

data class TransitData(
    val validFrom: String,
    val serviceDate: String,
    val source: String,
    val crpLat: Double,
    val crpLon: Double,
    val waterbeachLat: Double,
    val waterbeachLon: Double,
    val cycleLastMileMin: Int,
    val shuttleLastMileMin: Int,
    val stops: List<Stop>
) {
    fun lastMileMin(mode: LastMileMode): Int =
        if (mode == LastMileMode.CYCLE) cycleLastMileMin else shuttleLastMileMin

    /**
     * Arrivals at Waterbeach covered by the bundled timetable. The asset is built
     * for the morning commute, so asking for an evening arrival would silently
     * fall back to the last morning train -- [coversArrival] lets the UI say so.
     */
    val serviceWindow: IntRange?
        get() {
            val arrivals = stops.flatMap { it.journeys }.map { it.arrWaterbeach }
            return if (arrivals.isEmpty()) null else arrivals.min()..arrivals.max()
        }

    fun coversArrival(arrivalAtWork: Minutes, mode: LastMileMode): Boolean {
        val w = serviceWindow ?: return false
        val neededAtWaterbeach = arrivalAtWork - lastMileMin(mode)
        return neededAtWaterbeach >= w.first && neededAtWaterbeach <= w.last + 90
    }
}

/** What the user set on the commute-setup screen. */
data class CommuteSettings(
    val arrivalAtWork: Minutes,
    val maxCommuteMin: Int,
    val lastMileMode: LastMileMode
)

/**
 * OpenRent records furnishing as two independent flags, so a landlord can offer
 * either. "furnished=1, unfurnished=1" shows on the site as "Furnishing at
 * tenant choice" -- confirmed against the details field on every listing in the
 * test fixture -- and therefore satisfies whichever option you pick.
 */
enum class Furnishing(val label: String) {
    ANY("Any"), FURNISHED("Furnished"), UNFURNISHED("Unfurnished")
}

data class SearchCriteria(
    val minPrice: Int?,
    val maxPrice: Int?,
    val minBeds: Int?,
    val maxBeds: Int?,
    val excludeShared: Boolean = true,
    val excludeStudentOnly: Boolean = true,
    val furnishing: Furnishing = Furnishing.ANY,
    val requireBillsIncluded: Boolean = false,
    /** Latest acceptable move-in, as days from today. Null means any. */
    val moveInBeforeDays: Int? = null,
    /** Longest tenancy you are willing to commit to, in months. Null means any. */
    val maxMinimumTenancyMonths: Int? = null
)

/** A property as scraped from OpenRent. */
data class Listing(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val pricePcm: Int,
    val bedrooms: Int,
    val bathrooms: Int,
    val isShared: Boolean,
    val isStudio: Boolean,
    val listedAtMs: Long,
    val furnished: Boolean = false,
    val unfurnished: Boolean = false,
    val studentsAllowed: Boolean = true,
    val nonStudentsAllowed: Boolean = true,
    val dssCovered: Boolean = false,
    val billsIncluded: Boolean = false,
    /** Days from today until available; negative means available already. */
    val availableFromDays: Int = 0,
    /** Landlord's required minimum tenancy in months; 0 means none stated. */
    val minimumTenancyMonths: Int = 0,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val details: List<String> = emptyList(),
    val letAgreed: Boolean = false,
    val lastUpdated: String = ""
) {
    /** OpenRent 301-redirects any slug to the canonical URL, so the id is enough. */
    val url: String get() = "https://www.openrent.co.uk/property-to-rent/p/$id/$id"

    /** Studios count as one bedroom so they compare sensibly against 1-beds. */
    val pricePerBedroom: Int get() = pricePcm / bedrooms.coerceAtLeast(1)

    /** Advertised to students and nobody else. */
    val isStudentOnly: Boolean get() = studentsAllowed && !nonStudentsAllowed

    val availableNow: Boolean get() = availableFromDays <= 0

    val furnishingLabel: String get() = when {
        furnished && unfurnished -> "Furnishing at tenant choice"
        furnished -> "Furnished"
        unfurnished -> "Unfurnished"
        else -> ""
    }

    fun satisfies(want: Furnishing): Boolean = when (want) {
        Furnishing.ANY -> true
        Furnishing.FURNISHED -> furnished
        Furnishing.UNFURNISHED -> unfurnished
    }
}

/**
 * A fully-costed door-to-door commute for one property.
 *
 * [totalMin] is time actually spent travelling, from leaving home to arriving at
 * Cambridge Research Park. It deliberately excludes time spent waiting at work
 * because the train got in early -- that is reported separately as [slackMin], so
 * an infrequent service is visible without inflating the commute itself.
 */
data class CommuteResult(
    val stop: Stop,
    val journey: Journey?,
    val accessMin: Int,
    val lastMileMin: Int,
    val leaveHomeBy: Minutes,
    val arriveAtWork: Minutes,
    val totalMin: Int,
    val slackMin: Int
) {
    val isCycleDirect: Boolean get() = journey == null
    val waitMin: Int
        get() = journey?.let { (it.dep - (leaveHomeBy + accessMin)).coerceAtLeast(0) } ?: 0
}

data class ScoredListing(val listing: Listing, val commute: CommuteResult)

/**
 * [explanation] is shown under the sort bar, because several of these are not
 * self-evident and one of them (LEAVE_LATEST) is easy to confuse with COMMUTE.
 */
enum class SortOption(val label: String, val explanation: String) {
    COMMUTE("Commute", "Shortest door-to-door travel time first"),
    LEAVE_LATEST("Lie-in", "Latest you can leave home and still arrive on time"),
    PRICE("Price", "Cheapest per calendar month first"),
    PRICE_PER_BED("£ per bed", "Cheapest per bedroom, to compare 1- and 2-beds fairly"),
    NEWEST("Newest", "Most recently listed first"),
    AVAILABLE_SOON("Available", "Ready to move into soonest first")
}
