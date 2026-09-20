package com.openrent.cambridge.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openrent.cambridge.model.CommuteSettings
import com.openrent.cambridge.model.LastMileMode
import com.openrent.cambridge.model.Stop
import com.openrent.cambridge.model.TransitData
import com.openrent.cambridge.model.toHhMm

@Composable
fun CommuteSetupScreen(
    settings: CommuteSettings,
    transit: TransitData?,
    viableStops: List<Stop>,
    areaNames: List<String>,
    onSettingsChange: ((CommuteSettings) -> CommuteSettings) -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Cambridge Commute",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Destination: Cambridge Research Park",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Arrive at work by")
        OutlinedButton(
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        onSettingsChange { it.copy(arrivalAtWork = hour * 60 + minute) }
                    },
                    settings.arrivalAtWork / 60,
                    settings.arrivalAtWork % 60,
                    true
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(settings.arrivalAtWork.toHhMm()) }

        SectionLabel("Max acceptable commute: ${settings.maxCommuteMin} min")
        Slider(
            value = settings.maxCommuteMin.toFloat(),
            onValueChange = { v ->
                onSettingsChange { it.copy(maxCommuteMin = (v / 5).toInt() * 5) }
            },
            valueRange = 15f..90f,
            steps = 14,
            modifier = Modifier.fillMaxWidth()
        )

        SectionLabel("Last mile (Waterbeach to CRP)")
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            LastMileMode.entries.forEach { mode ->
                RadioButton(
                    selected = settings.lastMileMode == mode,
                    onClick = { onSettingsChange { it.copy(lastMileMode = mode) } }
                )
                val mins = transit?.lastMileMin(mode)
                Text("${mode.label}${if (mins != null) " ($mins mins)" else ""}")
                Spacer(Modifier.padding(horizontal = 8.dp))
            }
        }

        // The point of the max-commute control: show what it actually selected.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                if (transit == null) {
                    Text("Loading timetable...")
                } else if (viableStops.isEmpty()) {
                    Text(
                        "No areas reachable in ${settings.maxCommuteMin} min",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Allow a longer commute, or arrive later.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    if (!transit.coversArrival(settings.arrivalAtWork, settings.lastMileMode)) {
                        Text(
                            "This timetable covers morning arrivals only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        "${areaNames.size} areas within reach",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        areaNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Timetable valid from ${transit.validFrom}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Button(
            onClick = onContinue,
            enabled = viableStops.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) { Text("Continue to search") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
