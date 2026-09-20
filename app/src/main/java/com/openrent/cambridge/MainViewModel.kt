package com.openrent.cambridge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openrent.cambridge.data.CommuteCalculator
import com.openrent.cambridge.data.SearchRepository
import com.openrent.cambridge.data.TransitRepository
import com.openrent.cambridge.model.CommuteSettings
import com.openrent.cambridge.model.LastMileMode
import com.openrent.cambridge.model.ScoredListing
import com.openrent.cambridge.model.SearchCriteria
import com.openrent.cambridge.model.SortOption
import com.openrent.cambridge.model.Stop
import com.openrent.cambridge.model.TransitData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val transitRepo = TransitRepository(app)
    private val searchRepo = SearchRepository()

    private val _transit = MutableStateFlow<TransitData?>(null)
    val transit: StateFlow<TransitData?> = _transit.asStateFlow()

    private val _settings = MutableStateFlow(
        CommuteSettings(
            arrivalAtWork = 9 * 60,
            maxCommuteMin = 45,
            lastMileMode = LastMileMode.CYCLE
        )
    )
    val settings: StateFlow<CommuteSettings> = _settings.asStateFlow()

    private val _viableStops = MutableStateFlow<List<Stop>>(emptyList())
    val viableStops: StateFlow<List<Stop>> = _viableStops.asStateFlow()

    private val _results = MutableStateFlow<List<ScoredListing>>(emptyList())
    val results: StateFlow<List<ScoredListing>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _searchRun = MutableStateFlow(false)
    val searchRun: StateFlow<Boolean> = _searchRun.asStateFlow()

    private val _sort = MutableStateFlow(SortOption.COMMUTE)
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _transit.value = transitRepo.load()
                recomputeViable()
            } catch (e: Exception) {
                _error.value = "Could not load timetable data: ${e.message}"
            }
        }
    }

    fun updateSettings(transform: (CommuteSettings) -> CommuteSettings) {
        _settings.value = transform(_settings.value)
        recomputeViable()
    }

    fun setSort(option: SortOption) {
        _sort.value = option
    }

    private fun recomputeViable() {
        val data = _transit.value ?: return
        _viableStops.value = CommuteCalculator.viableStops(data, _settings.value)
    }

    /** Distinct areas that will actually be searched, for the setup screen. */
    fun selectedAreaNames(): List<String> =
        _viableStops.value.map { it.areaName }.distinct().sorted()

    fun sortedResults(): List<ScoredListing> = when (_sort.value) {
        SortOption.COMMUTE ->
            _results.value.sortedBy { it.commute.totalMin }
        // Not the same as COMMUTE: a slightly longer journey on a later train can
        // still mean a later alarm, which is usually what you actually care about.
        SortOption.LEAVE_LATEST ->
            _results.value.sortedByDescending { it.commute.leaveHomeBy }
        SortOption.PRICE ->
            _results.value.sortedBy { it.listing.pricePcm }
        SortOption.PRICE_PER_BED ->
            _results.value.sortedBy { it.listing.pricePerBedroom }
        SortOption.NEWEST ->
            _results.value.sortedByDescending { it.listing.listedAtMs }
        SortOption.AVAILABLE_SOON ->
            _results.value.sortedBy { it.listing.availableFromDays }
    }

    fun search(criteria: SearchCriteria) {
        val data = _transit.value ?: run {
            _error.value = "Timetable data is not loaded yet."
            return
        }
        if (_isSearching.value) return

        _isSearching.value = true
        _searchRun.value = true
        _error.value = null
        _results.value = emptyList()

        viewModelScope.launch {
            try {
                val found = searchRepo.search(
                    data = data,
                    settings = _settings.value,
                    criteria = criteria,
                    onProgress = { _status.value = it }
                )
                _results.value = found
                if (found.isEmpty() && _viableStops.value.isEmpty()) {
                    _error.value = "No areas are reachable within " +
                        "${_settings.value.maxCommuteMin} minutes. Try allowing a longer commute."
                }
            } catch (e: Exception) {
                _error.value = "Search failed: ${e.message ?: "check your connection"}"
            } finally {
                _isSearching.value = false
                _status.value = ""
            }
        }
    }
}
