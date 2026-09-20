package com.openrent.cambridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openrent.cambridge.model.CommuteResult
import com.openrent.cambridge.model.ScoredListing
import com.openrent.cambridge.model.toHhMm

private val Good = Color(0xFF2E7D32)

@Composable
fun ListingCard(scored: ScoredListing, maxCommuteMin: Int, onClick: () -> Unit) {
    val (listing, commute) = scored

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = listing.title.ifBlank { "Property ${listing.id}" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "£${listing.pricePcm} pcm",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(
                    buildString {
                        append("${listing.bedrooms} bed")
                        if (listing.bedrooms > 1) append(" · £${listing.pricePerBedroom}/bed")
                        if (listing.bathrooms > 0) append(" · ${listing.bathrooms} bath")
                        if (listing.isShared) append(" · shared")
                        if (listing.letAgreed) append(" · LET AGREED")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val attributes = buildList {
                listing.furnishingLabel.takeIf { it.isNotBlank() }?.let(::add)
                if (listing.billsIncluded) add("Bills included")
                if (listing.dssCovered) add("DSS / LHA ok")
                if (listing.isStudentOnly) add("Students only")
                add(
                    if (listing.availableNow) "Available now"
                    else "Available in ${listing.availableFromDays} days"
                )
                if (listing.minimumTenancyMonths > 0) {
                    add("${listing.minimumTenancyMonths} mo min tenancy")
                }
            }
            if (attributes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    attributes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "${commute.totalMin} min door to door",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (commute.totalMin <= maxCommuteMin * 2 / 3) Good
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Leave home by ${commute.leaveHomeBy.toHhMm()}, " +
                    "arrive ${commute.arriveAtWork.toHhMm()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (commute.slackMin >= 10) {
                Text(
                    "Gets you in ${commute.slackMin} min early - next service is later",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier.padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                CommuteLines(commute)
            }

            if (listing.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    listing.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CommuteLines(commute: CommuteResult) {
    val small = MaterialTheme.typography.bodySmall

    if (commute.isCycleDirect) {
        Text("🚲 Cycle straight to CRP: ${commute.totalMin} min", style = small)
        return
    }

    Text("🚲 Cycle to ${commute.stop.name}: ${commute.accessMin} min", style = small)
    commute.journey?.legs?.forEach { leg ->
        val icon = if (leg.mode == "rail") "🚆" else "🚌"
        Text(
            "$icon ${leg.dep.toHhMm()} ${leg.from} → ${leg.to} ${leg.arr.toHhMm()}" +
                if (leg.route.isNotBlank()) "  (${leg.route})" else "",
            style = small
        )
    }
    val lastMileIcon = if (commute.lastMileMin <= 7) "🚲" else "🚌"
    Text("$lastMileIcon Waterbeach → CRP: ${commute.lastMileMin} min", style = small)
}
