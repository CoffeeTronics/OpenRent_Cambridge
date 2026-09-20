package com.openrent.cambridge

import com.openrent.cambridge.data.OpenRentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the scraping contract against a silent OpenRent redesign, using a real
 * search page captured on 2026-09-19 (Ely, prices_min=800, bedrooms_max=2).
 *
 * If these fail, OpenRent changed its page format: re-run the step-0 probes and
 * update both the parser and this fixture.
 */
class OpenRentParserTest {

    private val html: String by lazy {
        javaClass.classLoader!!
            .getResourceAsStream("openrent_search_ely.html")!!
            .bufferedReader().use { it.readText() }
    }

    @Test
    fun `parses every property on the page, not just the rendered cards`() {
        val listings = OpenRentClient.parseListings(html)
        // The page renders 20 cards but the embedded arrays describe all 42.
        assertEquals(42, listings.size)
    }

    @Test
    fun `every listing carries real coordinates`() {
        OpenRentClient.parseListings(html).forEach { l ->
            assertTrue(
                "lat out of Cambridgeshire range: ${l.lat}",
                l.lat in 52.0..52.6
            )
            assertTrue(
                "lon out of Cambridgeshire range: ${l.lon}",
                l.lon in -0.2..0.6
            )
        }
    }

    @Test
    fun `parses the first listing exactly`() {
        val first = OpenRentClient.parseListings(html).first()
        assertEquals(3036978L, first.id)
        assertEquals(52.401947, first.lat, 1e-6)
        assertEquals(0.2514547, first.lon, 1e-6)
        assertEquals(495, first.pricePcm)
        assertEquals(4, first.bedrooms)
        assertTrue(first.isShared)
    }

    @Test
    fun `listing dates come from the page rather than being invented`() {
        val listings = OpenRentClient.parseListings(html)
        assertTrue(listings.all { it.listedAtMs > 1_000_000_000_000L })
        // Distinct dates prove these are real values, not a single "now".
        assertTrue(listings.map { it.listedAtMs }.distinct().size > 10)
    }

    @Test
    fun `server side filters are loose, which is why we filter again`() {
        // This fixture was fetched with prices_min=800 and bedrooms_max=2.
        val listings = OpenRentClient.parseListings(html)
        assertTrue(
            "expected OpenRent to return out-of-range results",
            listings.any { it.pricePcm < 800 } && listings.any { it.bedrooms > 2 }
        )
    }

    @Test
    fun `array extraction handles a missing array`() {
        assertTrue(OpenRentClient.extractArray(html, "NO_SUCH_ARRAY").isEmpty())
    }

    @Test
    fun `a page with no property arrays yields no listings`() {
        assertTrue(OpenRentClient.parseListings("<html><body>nothing</body></html>").isEmpty())
    }

    @Test
    fun `listing url embeds the id so OpenRent can redirect to the canonical page`() {
        val first = OpenRentClient.parseListings(html).first()
        assertTrue(first.url.endsWith("/${first.id}"))
        assertTrue(first.url.startsWith("https://www.openrent.co.uk/property-to-rent/"))
    }

    @Test
    fun `let agreed status is read from the search page`() {
        val listings = OpenRentClient.parseListings(html)
        // In this fixture only two of the 42 Ely properties were still available;
        // that is typical, and why let-agreed results are dropped automatically.
        assertEquals(40, listings.count { it.letAgreed })
        assertEquals(2, listings.count { !it.letAgreed })
    }

    @Test
    fun `the two available properties are the shared rooms in this fixture`() {
        val available = OpenRentClient.parseListings(html).filter { !it.letAgreed }
        assertEquals(setOf(3036978L, 3026702L), available.map { it.id }.toSet())
        assertTrue(available.all { it.isShared })
    }

    @Test
    fun `let agreed defaults to available when the array is missing`() {
        // A page without islivelistBool must not mark everything as let agreed,
        // which would silently empty every search.
        val stripped = html.replace(Regex("""var\s+islivelistBool\s*=\s*\[.*?]\s*;""",
            RegexOption.DOT_MATCHES_ALL), "")
        val listings = OpenRentClient.parseListings(stripped)
        assertEquals(42, listings.size)
        assertTrue(listings.none { it.letAgreed })
    }
}
