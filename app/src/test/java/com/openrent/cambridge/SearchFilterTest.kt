package com.openrent.cambridge

import com.openrent.cambridge.data.OpenRentClient
import com.openrent.cambridge.data.SearchRepository
import com.openrent.cambridge.model.Furnishing
import com.openrent.cambridge.model.Listing
import com.openrent.cambridge.model.SearchCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the filters applied to real listing data in [SearchRepository.matches]. */
class SearchFilterTest {

    private val repo = SearchRepository()

    private val fixture: List<Listing> by lazy {
        val html = javaClass.classLoader!!
            .getResourceAsStream("openrent_search_ely.html")!!
            .bufferedReader().use { it.readText() }
        OpenRentClient.parseListings(html)
    }

    /** Wide open: only let-agreed and zero-price listings are rejected. */
    private val anything = SearchCriteria(
        minPrice = null, maxPrice = null, minBeds = null, maxBeds = null,
        excludeShared = false, excludeStudentOnly = false
    )

    private fun listing(
        shared: Boolean = false,
        furnished: Boolean = false,
        unfurnished: Boolean = false,
        students: Boolean = true,
        nonStudents: Boolean = true,
        dss: Boolean = false,
        bills: Boolean = false,
        availableIn: Int = 0,
        tenancy: Int = 0
    ) = Listing(
        id = 1, lat = 52.4, lon = 0.26, pricePcm = 1000, bedrooms = 2, bathrooms = 1,
        isShared = shared, isStudio = false, listedAtMs = 1L,
        furnished = furnished, unfurnished = unfurnished,
        studentsAllowed = students, nonStudentsAllowed = nonStudents,
        dssCovered = dss, billsIncluded = bills,
        availableFromDays = availableIn, minimumTenancyMonths = tenancy
    )

    @Test
    fun `shared properties are excluded only when asked`() {
        val shared = listing(shared = true)
        assertFalse(repo.matches(shared, anything.copy(excludeShared = true)))
        assertTrue(repo.matches(shared, anything.copy(excludeShared = false)))
    }

    @Test
    fun `student-only means students yes, everyone else no`() {
        val studentOnly = listing(students = true, nonStudents = false)
        val openToAll = listing(students = true, nonStudents = true)
        assertTrue(studentOnly.isStudentOnly)
        assertFalse(openToAll.isStudentOnly)
        assertFalse(repo.matches(studentOnly, anything.copy(excludeStudentOnly = true)))
        assertTrue(repo.matches(openToAll, anything.copy(excludeStudentOnly = true)))
    }

    @Test
    fun `a landlord offering either furnishing matches whichever you pick`() {
        val either = listing(furnished = true, unfurnished = true)
        assertTrue(repo.matches(either, anything.copy(furnishing = Furnishing.FURNISHED)))
        assertTrue(repo.matches(either, anything.copy(furnishing = Furnishing.UNFURNISHED)))
        assertEquals("Furnishing at tenant choice", either.furnishingLabel)
    }

    @Test
    fun `furnished and unfurnished filters are exclusive when the landlord is not`() {
        val furnishedOnly = listing(furnished = true)
        assertTrue(repo.matches(furnishedOnly, anything.copy(furnishing = Furnishing.FURNISHED)))
        assertFalse(repo.matches(furnishedOnly, anything.copy(furnishing = Furnishing.UNFURNISHED)))
        assertTrue(repo.matches(furnishedOnly, anything.copy(furnishing = Furnishing.ANY)))
    }

    @Test
    fun `bills filter only applies when switched on`() {
        val plain = listing()
        assertTrue(repo.matches(plain, anything))
        assertFalse(repo.matches(plain, anything.copy(requireBillsIncluded = true)))
        assertTrue(repo.matches(listing(bills = true), anything.copy(requireBillsIncluded = true)))
    }

    @Test
    fun `DSS listings are always excluded, with no way to switch it off`() {
        val dssListing = listing(dss = true)
        assertFalse(repo.matches(dssListing, anything))
        assertFalse(repo.matches(dssListing, SearchCriteria(null, null, null, null)))
        // The same property without the DSS flag passes, so nothing else rejects it.
        assertTrue(repo.matches(listing(dss = false), anything))
    }

    @Test
    fun `move-in filter keeps anything available sooner`() {
        val c = anything.copy(moveInBeforeDays = 30)
        assertTrue(repo.matches(listing(availableIn = -100), c))  // already available
        assertTrue(repo.matches(listing(availableIn = 30), c))
        assertFalse(repo.matches(listing(availableIn = 60), c))
    }

    @Test
    fun `move-in filter allows a day of slack at the boundary`() {
        // OpenRent's reference date is off by about a day, so a borderline
        // property is shown rather than silently hidden.
        val c = anything.copy(moveInBeforeDays = 30)
        assertTrue(repo.matches(listing(availableIn = 31), c))
        assertFalse(repo.matches(listing(availableIn = 32), c))
    }

    @Test
    fun `tenancy filter hides longer commitments and keeps unstated ones`() {
        val c = anything.copy(maxMinimumTenancyMonths = 6)
        assertTrue(repo.matches(listing(tenancy = 0), c))   // no minimum stated
        assertTrue(repo.matches(listing(tenancy = 6), c))
        assertFalse(repo.matches(listing(tenancy = 12), c))
        assertTrue(repo.matches(listing(tenancy = 12), anything))
    }

    @Test
    fun `let agreed is rejected whatever else is asked for`() {
        val agreed = listing().copy(letAgreed = true)
        assertFalse(repo.matches(agreed, anything))
    }

    @Test
    fun `the new fields are populated from a real search page`() {
        // The fixture's two available properties are both furnished shared rooms
        // with bills included, per the details field on the live site.
        val available = fixture.filter { !it.letAgreed }
        assertTrue(available.isNotEmpty())
        assertTrue(available.all { it.isShared })
        assertTrue(available.all { it.furnished })
        assertTrue(available.all { it.billsIncluded })
    }

    @Test
    fun `real pages carry a spread of tenancies and availability`() {
        assertTrue(fixture.map { it.minimumTenancyMonths }.distinct().size > 1)
        assertTrue(fixture.any { it.availableNow })
        assertTrue(fixture.any { !it.availableNow })
        assertTrue(fixture.any { it.unfurnished })
        assertTrue(fixture.any { it.furnished })
    }

    @Test
    fun `default criteria exclude shared and student-only`() {
        val defaults = SearchCriteria(800, 2000, 1, 2)
        assertTrue(defaults.excludeShared)
        assertTrue(defaults.excludeStudentOnly)
        assertEquals(Furnishing.ANY, defaults.furnishing)
        assertFalse(repo.matches(listing(shared = true), defaults))
    }
}
