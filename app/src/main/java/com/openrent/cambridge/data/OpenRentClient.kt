package com.openrent.cambridge.data

import android.util.Log
import com.openrent.cambridge.model.Listing
import com.openrent.cambridge.model.SearchCriteria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup

/**
 * Reads OpenRent search results.
 *
 * OpenRent renders only the first 20 result cards into the HTML, but it also
 * emits parallel JavaScript arrays describing *every* match on the page --
 * including exact coordinates. Those arrays are the primary source here: one
 * request yields every property, already located, so there is no need to open
 * each listing to find out where it is.
 *
 *     var PROPERTYIDS = [ 3036978, 3026702, ... ];
 *     var PROPERTYLISTLATITUDES = [ 52.401947, ... ];
 *     var PROPERTYLISTLONGITUDES = [ 0.2514547, ... ];
 *     var prices = [ 495, 850, ... ];  var bedrooms = [ ... ];
 *
 * Titles and descriptions are not in those arrays, so surviving listings are
 * hydrated afterwards from /search/propertiesbyid (JSON, max 20 ids per call).
 *
 * Verified against live OpenRent on 2026-09-19. The format is undocumented and
 * may change; ParserTest guards it with saved fixtures.
 */
class OpenRentClient {

    companion object {
        private const val TAG = "OpenRentClient"
        private const val BASE = "https://www.openrent.co.uk"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** The endpoint returns an empty array above this, rather than truncating. */
        const val HYDRATE_BATCH = 20

        private fun arrayRegex(name: String) =
            Regex("""var\s+$name\s*=\s*\[(.*?)]\s*;""", RegexOption.DOT_MATCHES_ALL)

        /** Pulls one `var NAME = [...]` array out of the page source. */
        fun extractArray(html: String, name: String): List<String> =
            arrayRegex(name).find(html)
                ?.groupValues?.get(1)
                ?.split(',')
                ?.map { it.trim().trim('\'', '"') }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        /**
         * Parses the embedded arrays into listings. Exposed for unit testing
         * against saved page fixtures.
         */
        fun parseListings(html: String): List<Listing> {
            val ids = extractArray(html, "PROPERTYIDS")
            if (ids.isEmpty()) {
                Log.w(TAG, "No PROPERTYIDS array found - page format may have changed")
                return emptyList()
            }
            val lats = extractArray(html, "PROPERTYLISTLATITUDES")
            val lons = extractArray(html, "PROPERTYLISTLONGITUDES")
            val prices = extractArray(html, "prices")
            val beds = extractArray(html, "bedrooms")
            val baths = extractArray(html, "bathrooms")
            val shared = extractArray(html, "isshared")
            val studio = extractArray(html, "isstudio")
            val listed = extractArray(html, "dateFirstListedMs")
            // islivelistBool is 1 while a property is still available and 0 once it
            // is let agreed. Verified against letAgreed from /search/propertiesbyid
            // on 120 listings across three areas: exact agreement, no exceptions.
            // Reading it here lets let-agreed properties be dropped from the full
            // result set, before we spend any hydrate requests on them.
            val live = extractArray(html, "islivelistBool")
            val furnished = extractArray(html, "furnished")
            val unfurnished = extractArray(html, "unfurnished")
            val students = extractArray(html, "students")
            val nonStudents = extractArray(html, "nonStudents")
            val dss = extractArray(html, "rentCoveredDssOrPreferred")
            val bills = extractArray(html, "bills")
            // Days until available, relative to today; negative means already free.
            // Checked against a listing advertising 23 November 2026 with a value of
            // 64, so the reference is effectively tomorrow -- close enough for a
            // move-in filter, which applies a day of tolerance.
            val availableFrom = extractArray(html, "availableFrom")
            val minTenancy = extractArray(html, "minimumTenancy")

            // Every array is index-aligned with PROPERTYIDS. If one came back short
            // the page changed shape, so fall back to blanks rather than misaligning.
            fun <T> at(list: List<String>, i: Int, convert: (String) -> T?, fallback: T): T =
                list.getOrNull(i)?.let(convert) ?: fallback

            return ids.indices.mapNotNull { i ->
                val id = ids[i].toLongOrNull() ?: return@mapNotNull null
                val lat = lats.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = lons.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                Listing(
                    id = id,
                    lat = lat,
                    lon = lon,
                    pricePcm = at(prices, i, { it.toDouble().toInt() }, 0),
                    bedrooms = at(beds, i, String::toIntOrNull, 0),
                    bathrooms = at(baths, i, String::toIntOrNull, 0),
                    isShared = at(shared, i, { it == "1" }, false),
                    isStudio = at(studio, i, { it == "1" }, false),
                    listedAtMs = at(listed, i, String::toLongOrNull, 0L),
                    letAgreed = at(live, i, { it == "0" }, false),
                    furnished = at(furnished, i, { it == "1" }, false),
                    unfurnished = at(unfurnished, i, { it == "1" }, false),
                    studentsAllowed = at(students, i, { it == "1" }, true),
                    nonStudentsAllowed = at(nonStudents, i, { it == "1" }, true),
                    dssCovered = at(dss, i, { it == "1" }, false),
                    billsIncluded = at(bills, i, { it == "1" }, false),
                    availableFromDays = at(availableFrom, i, String::toIntOrNull, 0),
                    minimumTenancyMonths = at(minTenancy, i, String::toIntOrNull, 0)
                )
            }
        }
    }

    /**
     * Fetches one search area. The price/bed query parameters are sent, but
     * OpenRent applies them loosely -- a prices_min=800 search still returns
     * sub-£800 rooms -- so [SearchRepository] filters again on the real values.
     */
    suspend fun search(term: String, slug: String, criteria: SearchCriteria): List<Listing> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$BASE/properties-to-rent/$slug")
                append("?term=").append(java.net.URLEncoder.encode(term, "UTF-8"))
                criteria.minPrice?.let { append("&prices_min=$it") }
                criteria.maxPrice?.let { append("&prices_max=$it") }
                criteria.minBeds?.let { append("&bedrooms_min=$it") }
                criteria.maxBeds?.let { append("&bedrooms_max=$it") }
            }
            try {
                val html = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .header("Referer", "$BASE/")
                    .timeout(30_000)
                    .maxBodySize(0)
                    .execute()
                    .body()
                val listings = parseListings(html)
                Log.d(TAG, "$term -> ${listings.size} listings")
                listings
            } catch (e: Exception) {
                Log.e(TAG, "search failed for $term", e)
                throw e
            }
        }

    /** Fills in title, description and image for listings we intend to show. */
    suspend fun hydrate(listings: List<Listing>): List<Listing> =
        withContext(Dispatchers.IO) {
            val byId = listings.associateBy { it.id }
            val out = listings.toMutableList()
            listings.chunked(HYDRATE_BATCH).forEach { chunk ->
                val query = chunk.joinToString("&") { "ids=${it.id}" }
                try {
                    val body = Jsoup.connect("$BASE/search/propertiesbyid?$query")
                        .userAgent(UA)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "$BASE/")
                        .ignoreContentType(true)
                        .timeout(30_000)
                        .maxBodySize(0)
                        .execute()
                        .body()
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val base = byId[o.optLong("id")] ?: continue
                        val details = o.optJSONArray("details")?.let { d ->
                            (0 until d.length()).map { d.optString(it) }
                        } ?: emptyList()
                        val idx = out.indexOfFirst { it.id == base.id }
                        if (idx >= 0) {
                            out[idx] = base.copy(
                                title = o.optString("title", base.title),
                                description = o.optString("description", "").trim(),
                                imageUrl = o.optString("imageUrl", "")
                                    .let { if (it.startsWith("//")) "https:$it" else it },
                                details = details,
                                letAgreed = o.optBoolean("letAgreed", base.letAgreed),
                                lastUpdated = o.optString("lastUpdated", ""),
                                pricePcm = o.optDouble("rentPerMonth", base.pricePcm.toDouble())
                                    .toInt()
                                    .takeIf { it > 0 } ?: base.pricePcm
                            )
                        }
                    }
                } catch (e: Exception) {
                    // A failed hydrate costs us a title, not the result itself.
                    Log.e(TAG, "hydrate batch failed", e)
                }
            }
            out
        }
}
