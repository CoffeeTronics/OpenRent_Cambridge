package com.openrent.cambridge.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openrent.cambridge.model.Furnishing
import com.openrent.cambridge.model.SearchCriteria
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Tenancy lengths offered, in months. Null is "any". */
private val TENANCY_OPTIONS = listOf<Int?>(null, 6, 12, 18, 24)

@Composable
fun SearchScreen(
    areaCount: Int,
    maxCommuteMin: Int,
    onSearch: (SearchCriteria) -> Unit
) {
    var minPrice by remember { mutableStateOf("800") }
    var maxPrice by remember { mutableStateOf("2000") }
    var minBeds by remember { mutableStateOf("1") }
    var maxBeds by remember { mutableStateOf("2") }

    var excludeShared by remember { mutableStateOf(true) }
    var excludeStudentOnly by remember { mutableStateOf(true) }
    var furnishing by remember { mutableStateOf(Furnishing.ANY) }
    var requireBills by remember { mutableStateOf(false) }
    var maxTenancy by remember { mutableStateOf<Int?>(null) }
    var moveInBeforeMillis by remember { mutableStateOf<Long?>(null) }

    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.UK) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "$areaCount areas within $maxCommuteMin min of Cambridge Research Park",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Let agreed and DSS/LHA listings are excluded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(minPrice, { minPrice = it }, "Min £ PCM", Modifier.weight(1f))
            NumberField(maxPrice, { maxPrice = it }, "Max £ PCM", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(minBeds, { minBeds = it }, "Min beds", Modifier.weight(1f))
            NumberField(maxBeds, { maxBeds = it }, "Max beds", Modifier.weight(1f))
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        CheckRow("Exclude shared houses and rooms", excludeShared) { excludeShared = it }
        CheckRow("Exclude student-only properties", excludeStudentOnly) { excludeStudentOnly = it }
        CheckRow("Only with bills included", requireBills) { requireBills = it }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        SectionHeading("Furnishing")
        ChipRow(
            items = Furnishing.entries.toList(),
            label = { it.label },
            isSelected = { furnishing == it },
            onSelect = { furnishing = it },
            // "Unfurnished" needs half the width to render without truncating.
            perRow = 2
        )
        Text(
            "Landlords offering either still match.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionHeading("Move in before")
        OutlinedButton(
            onClick = {
                val cal = Calendar.getInstance()
                moveInBeforeMillis?.let { cal.timeInMillis = it }
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        moveInBeforeMillis = Calendar.getInstance().apply {
                            set(year, month, day, 12, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(moveInBeforeMillis?.let { dateFormat.format(it) } ?: "Any date")
        }
        if (moveInBeforeMillis != null) {
            TextButtonRow("Clear move-in date") { moveInBeforeMillis = null }
        }

        SectionHeading("Longest tenancy you would commit to")
        ChipRow(
            items = TENANCY_OPTIONS,
            label = { months -> months?.let { "$it mo" } ?: "Any" },
            isSelected = { maxTenancy == it },
            onSelect = { maxTenancy = it }
        )
        Text(
            "Hides properties demanding a longer commitment than this.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))


        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                onSearch(
                    SearchCriteria(
                        minPrice = minPrice.toIntOrNull(),
                        maxPrice = maxPrice.toIntOrNull(),
                        minBeds = minBeds.toIntOrNull(),
                        maxBeds = maxBeds.toIntOrNull(),
                        excludeShared = excludeShared,
                        excludeStudentOnly = excludeStudentOnly,
                        furnishing = furnishing,
                        requireBillsIncluded = requireBills,
                        moveInBeforeDays = moveInBeforeMillis?.let { daysFromToday(it) },
                        maxMinimumTenancyMonths = maxTenancy
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) { Text("Search OpenRent") }
    }
}

/** Whole days from today to [millis]; OpenRent counts availability the same way. */
private fun daysFromToday(millis: Long): Int {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return TimeUnit.MILLISECONDS.toDays(millis - today).toInt()
}

/**
 * Lays chips out a fixed number per row, each taking an equal share of the width.
 *
 * Deliberately built from Row/Column rather than FlowRow: this module compiles
 * against foundation 1.7.2 (pinned by the Compose BOM) but navigation-compose
 * pulls 1.9.2 in at runtime, and FlowRow's signature differs between the two,
 * which crashed the app with NoSuchMethodError. Row and Column are binary-stable
 * across those versions.
 */
@Composable
private fun <T> ChipRow(
    items: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    perRow: Int = 3
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    FilterChip(
                        selected = isSelected(item),
                        onClick = { onSelect(item) },
                        label = {
                            Text(
                                label(item),
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keep chip widths consistent when the last row is short.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}
