package com.bookchat.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class DiagnosticsDirection { In, Out, System }

data class DiagnosticsEntry(
    val direction: DiagnosticsDirection,
    val text: String,
    val time: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
)

@Composable
fun DiagnosticsPanel(
    expanded: Boolean,
    entries: List<DiagnosticsEntry>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "IRC Diagnostics" + if (entries.isNotEmpty()) " (${entries.size})" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        reverseLayout = true,
                    ) {
                        items(entries) { entry ->
                            DiagnosticsEntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsEntryRow(entry: DiagnosticsEntry) {
    val (arrow, color) = when (entry.direction) {
        DiagnosticsDirection.In     -> "←" to MaterialTheme.colorScheme.onSurface
        DiagnosticsDirection.Out    -> "→" to MaterialTheme.colorScheme.primary
        DiagnosticsDirection.System -> "◉" to Color(0xFFFFA000)
    }
    Text(
        text = "$arrow ${entry.time} ${entry.text}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
