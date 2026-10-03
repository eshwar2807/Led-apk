package com.eshwar.reelplay.library

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** Remembers the library's sort and filter across launches. */
class LibraryViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var sort: LibrarySort
        get() = LibrarySort(
            enumOrNull<SortField>(prefs.getString("sort_field", null)) ?: SortField.DATE_ADDED,
            prefs.getBoolean("sort_ascending", false),
        )
        set(value) = prefs.edit {
            putString("sort_field", value.field.name)
            putBoolean("sort_ascending", value.ascending)
        }

    var filter: PlayFilter
        get() = enumOrNull<PlayFilter>(prefs.getString("filter", null)) ?: PlayFilter.ALL
        set(value) = prefs.edit { putString("filter", value.name) }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        enumValues<T>().firstOrNull { it.name == name }
}

/** MX-style "Sort by" dialog: pick a field, then which way round, plus what to show. */
@Composable
fun SortDialog(
    current: LibrarySort,
    currentFilter: PlayFilter,
    onDismiss: () -> Unit,
    onApply: (LibrarySort, PlayFilter) -> Unit,
) {
    var field by remember { mutableStateOf(current.field) }
    var ascending by remember { mutableStateOf(current.ascending) }
    var filter by remember { mutableStateOf(currentFilter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort & filter") },
        confirmButton = { TextButton(onClick = { onApply(LibrarySort(field, ascending), filter) }) { Text("Apply") } },
        dismissButton = {
            TextButton(onClick = { onApply(LibrarySort(), PlayFilter.ALL) }) { Text("Reset") }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("Sort by", style = MaterialTheme.typography.titleSmall)
                SortField.entries.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { field = f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = field == f, onClick = { field = f })
                        Text(f.label)
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    SegmentedButton(
                        selected = ascending,
                        onClick = { ascending = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(field.ascendingLabel) }
                    SegmentedButton(
                        selected = !ascending,
                        onClick = { ascending = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(field.descendingLabel) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Show", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    PlayFilter.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { filter = f },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = filter == f, onClick = { filter = f })
                            Text(f.label)
                        }
                    }
                }
            }
        },
    )
}

/** A chip above the list saying how it's sorted/filtered when that's not the default. */
@Composable
fun SortSummary(sort: LibrarySort, filter: PlayFilter, onClick: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onClick,
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) },
        label = {
            Text(if (filter == PlayFilter.ALL) sort.label else "${filter.label} · ${sort.label}")
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** "3 Oct 2026", or "Today"/"Yesterday". */
fun formatDay(epochMs: Long): String = when {
    DateUtils.isToday(epochMs) -> "today"
    DateUtils.isToday(epochMs + DateUtils.DAY_IN_MILLIS) -> "yesterday"
    else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMs))
}

/** "5 min. ago", "2 hours ago", else the day. */
fun formatWhen(epochMs: Long): String {
    val ago = System.currentTimeMillis() - epochMs
    return if (ago in 0 until DateUtils.DAY_IN_MILLIS) {
        DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    } else {
        formatDay(epochMs)
    }
}
