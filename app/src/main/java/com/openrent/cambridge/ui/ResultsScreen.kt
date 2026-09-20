package com.openrent.cambridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openrent.cambridge.model.ScoredListing
import com.openrent.cambridge.model.SortOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    results: List<ScoredListing>,
    isSearching: Boolean,
    status: String,
    error: String?,
    searchRun: Boolean,
    sort: SortOption,
    maxCommuteMin: Int,
    onSortChange: (SortOption) -> Unit,
    onListingClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()

    // Re-sorting puts entirely different properties at the top, so keeping the old
    // scroll offset would leave you stranded mid-list looking at unrelated results.
    LaunchedEffect(sort) {
        if (results.isNotEmpty()) listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isSearching) "Searching..." else "${results.size} properties")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isSearching -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }

                error != null -> Centered {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Back") }
                }

                searchRun && results.isEmpty() -> Centered {
                    Text("No properties matched.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Everything found was either let agreed or outside your " +
                            "filters. Try a wider price range, more bedrooms, or a " +
                            "longer commute.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Change search") }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    SortBar(sort, onSortChange)
                    HorizontalDivider()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    items(results, key = { it.listing.id }) { scored ->
                        ListingCard(
                            scored = scored,
                            maxCommuteMin = maxCommuteMin,
                            onClick = { onListingClick(scored.listing.url) }
                        )
                    }
                    item {
                        Text(
                            "Let agreed and DSS/LHA listings are excluded. Commute times are " +
                                "estimates: OpenRent shows approximate locations, so " +
                                "allow a few minutes either way.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    }
                }
            }
        }
    }
}

/**
 * Sorting sits above the list rather than in the app bar: with six options it
 * needs room to say what each one does, and "Lie-in" in particular is not
 * guessable from the label alone.
 */
@Composable
private fun SortBar(sort: SortOption, onSortChange: (SortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(sort.label)
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.label)
                                    Text(
                                        option.explanation,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { onSortChange(option); expanded = false }
                        )
                    }
                }
            }
        }
        Text(
            sort.explanation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) { content() }
    }
}
