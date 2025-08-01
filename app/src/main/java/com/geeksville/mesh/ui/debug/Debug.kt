/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.geeksville.mesh.ui.debug

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.twotone.FilterAltOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.compose.ui.unit.sp
import androidx.datastore.core.IOException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.R
import com.geeksville.mesh.android.BuildUtils.warn
import com.geeksville.mesh.model.DebugViewModel
import com.geeksville.mesh.model.DebugViewModel.UiMeshLog
import com.geeksville.mesh.ui.common.components.CopyIconButton
import com.geeksville.mesh.ui.common.components.SimpleAlertDialog
import com.geeksville.mesh.ui.common.theme.AnnotationColor
import com.geeksville.mesh.ui.common.theme.AppTheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val REGEX_ANNOTATED_NODE_ID = Regex("\\(![0-9a-fA-F]{8}\\)$", RegexOption.MULTILINE)

@Suppress("LongMethod")
@Composable
internal fun DebugScreen(viewModel: DebugViewModel = hiltViewModel()) {
    val listState = rememberLazyListState()
    val logs by viewModel.meshLog.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val filterTexts by viewModel.filterTexts.collectAsStateWithLifecycle()
    val selectedLogId by viewModel.selectedLogId.collectAsStateWithLifecycle()

    var filterMode by remember { mutableStateOf(FilterMode.OR) }

    // Use the new filterLogs method to include decodedPayload in filtering
    val filteredLogs =
        remember(logs, filterTexts, filterMode) {
            viewModel.filterManager.filterLogs(logs, filterTexts, filterMode).toImmutableList()
        }

    LaunchedEffect(filteredLogs) { viewModel.updateFilteredLogs(filteredLogs) }

    val shouldAutoScroll by remember { derivedStateOf { listState.firstVisibleItemIndex < 3 } }
    if (shouldAutoScroll) {
        LaunchedEffect(filteredLogs) {
            if (!listState.isScrollInProgress) {
                listState.animateScrollToItem(0)
            }
        }
    }
    // Handle search result navigation
    LaunchedEffect(searchState) {
        if (searchState.currentMatchIndex >= 0 && searchState.currentMatchIndex < searchState.allMatches.size) {
            listState.requestScrollToItem(searchState.allMatches[searchState.currentMatchIndex].logIndex)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            stickyHeader {
                val animatedAlpha by
                    animateFloatAsState(targetValue = if (!listState.isScrollInProgress) 1.0f else 0f, label = "alpha")
                DebugSearchStateviewModelDefaults(
                    modifier = Modifier.graphicsLayer(alpha = animatedAlpha),
                    searchState = searchState,
                    filterTexts = filterTexts,
                    presetFilters = viewModel.presetFilters,
                    logs = logs,
                    filterMode = filterMode,
                    onFilterModeChange = { filterMode = it },
                )
            }
            items(filteredLogs, key = { it.uuid }) { log ->
                DebugItem(
                    modifier = Modifier.animateItem(),
                    log = log,
                    searchText = searchState.searchText,
                    isSelected = selectedLogId == log.uuid,
                    onLogClick = { viewModel.setSelectedLogId(if (selectedLogId == log.uuid) null else log.uuid) },
                )
            }
        }
    }
}

@Composable
internal fun DebugItem(
    log: UiMeshLog,
    modifier: Modifier = Modifier,
    searchText: String = "",
    isSelected: Boolean = false,
    onLogClick: () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth().padding(4.dp),
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (isSelected) {
                colorScheme.primary.copy(alpha = 0.1f)
            } else {
                colorScheme.surface
            },
        ),
        border =
        if (isSelected) {
            BorderStroke(2.dp, colorScheme.primary)
        } else {
            null
        },
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(if (isSelected) 12.dp else 8.dp).fillMaxWidth().clickable { onLogClick() },
            ) {
                DebugItemHeader(log = log, searchText = searchText, isSelected = isSelected, theme = colorScheme)
                val messageAnnotatedString = rememberAnnotatedLogMessage(log, searchText)
                Text(
                    text = messageAnnotatedString,
                    softWrap = false,
                    style =
                    TextStyle(
                        fontSize = if (isSelected) 12.sp else 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface,
                    ),
                )
                // Show decoded payload if available, with search highlighting
                if (!log.decodedPayload.isNullOrBlank()) {
                    DecodedPayloadBlock(
                        decodedPayload = log.decodedPayload,
                        isSelected = isSelected,
                        colorScheme = colorScheme,
                        searchText = searchText,
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugItemHeader(log: UiMeshLog, searchText: String, isSelected: Boolean, theme: ColorScheme) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = if (isSelected) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val typeAnnotatedString = rememberAnnotatedString(text = log.messageType, searchText = searchText)
        Text(
            text = typeAnnotatedString,
            modifier = Modifier.weight(1f),
            style =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = if (isSelected) 16.sp else 14.sp,
                color = theme.onSurface,
            ),
        )
        // Copy full log: message + decoded payload if present
        val fullLogText =
            remember(log.logMessage, log.decodedPayload) {
                buildString {
                    append(log.logMessage)
                    if (!log.decodedPayload.isNullOrBlank()) {
                        append("\n\nDecoded Payload:\n{")
                        append("\n")
                        append(log.decodedPayload)
                        append("\n}")
                    }
                }
            }
        CopyIconButton(valueToCopy = fullLogText, modifier = Modifier.padding(start = 8.dp))
        Icon(
            imageVector = Icons.Outlined.FileDownload,
            contentDescription = stringResource(id = R.string.logs),
            tint = Color.Gray.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp),
        )
        val dateAnnotatedString = rememberAnnotatedString(text = log.formattedReceivedDate, searchText = searchText)
        Text(
            text = dateAnnotatedString,
            style =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = if (isSelected) 14.sp else 12.sp,
                color = theme.onSurface,
            ),
        )
    }
}

@Composable
private fun rememberAnnotatedString(text: String, searchText: String): AnnotatedString {
    val theme = MaterialTheme.colorScheme
    val highlightStyle = SpanStyle(background = theme.primary.copy(alpha = 0.3f), color = theme.onSurface)

    return remember(text, searchText) {
        buildAnnotatedString {
            append(text)
            if (searchText.isNotEmpty()) {
                searchText.split(" ").forEach { term ->
                    Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
                        addStyle(style = highlightStyle, start = match.range.first, end = match.range.last + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberAnnotatedLogMessage(log: UiMeshLog, searchText: String): AnnotatedString {
    val theme = MaterialTheme.colorScheme
    val style = SpanStyle(color = AnnotationColor, fontStyle = FontStyle.Italic)
    val highlightStyle = SpanStyle(background = theme.primary.copy(alpha = 0.3f), color = theme.onSurface)

    return remember(log.uuid, searchText) {
        buildAnnotatedString {
            append(log.logMessage)

            // Add node ID annotations
            REGEX_ANNOTATED_NODE_ID.findAll(log.logMessage).toList().reversed().forEach {
                addStyle(style = style, start = it.range.first, end = it.range.last + 1)
            }

            // Add search highlight annotations
            if (searchText.isNotEmpty()) {
                searchText.split(" ").forEach { term ->
                    Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(log.logMessage).forEach { match ->
                        addStyle(style = highlightStyle, start = match.range.first, end = match.range.last + 1)
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun DebugPacketPreview() {
    AppTheme {
        DebugItem(
            UiMeshLog(
                uuid = "",
                messageType = "NodeInfo",
                formattedReceivedDate = "9/27/20, 8:00:58 PM",
                logMessage =
                "from: 2885173132\n" +
                    "decoded {\n" +
                    "   position {\n" +
                    "       altitude: 60\n" +
                    "       battery_level: 81\n" +
                    "       latitude_i: 411111136\n" +
                    "       longitude_i: -711111805\n" +
                    "       time: 1600390966\n" +
                    "   }\n" +
                    "}\n" +
                    "hop_limit: 3\n" +
                    "id: 1737414295\n" +
                    "rx_snr: 9.5\n" +
                    "rx_time: 316400569\n" +
                    "to: -1409790708",
            ),
        )
    }
}

@PreviewLightDark
@Composable
private fun DebugItemWithSearchHighlightPreview() {
    AppTheme {
        DebugItem(
            UiMeshLog(
                uuid = "1",
                messageType = "TextMessage",
                formattedReceivedDate = "9/27/20, 8:00:58 PM",
                logMessage = "Hello world! This is a test message with some keywords to search for.",
            ),
            searchText = "test message",
        )
    }
}

@PreviewLightDark
@Composable
private fun DebugItemPositionPreview() {
    AppTheme {
        DebugItem(
            UiMeshLog(
                uuid = "2",
                messageType = "Position",
                formattedReceivedDate = "9/27/20, 8:01:15 PM",
                logMessage = "Position update from node (!a1b2c3d4) at coordinates 40.7128, -74.0060",
            ),
        )
    }
}

@PreviewLightDark
@Composable
private fun DebugItemErrorPreview() {
    AppTheme {
        DebugItem(
            UiMeshLog(
                uuid = "3",
                messageType = "Error",
                formattedReceivedDate = "9/27/20, 8:02:30 PM",
                logMessage =
                "Connection failed: timeout after 30 seconds\n" +
                    "Retry attempt: 3/5\n" +
                    "Last known position: 40.7128, -74.0060",
            ),
        )
    }
}

@PreviewLightDark
@Composable
private fun DebugItemLongMessagePreview() {
    AppTheme {
        DebugItem(
            UiMeshLog(
                uuid = "4",
                messageType = "Waypoint",
                formattedReceivedDate = "9/27/20, 8:03:45 PM",
                logMessage =
                "Waypoint created:\n" +
                    "  Name: Home Base\n" +
                    "  Description: Primary meeting location\n" +
                    "  Latitude: 40.7128\n" +
                    "  Longitude: -74.0060\n" +
                    "  Altitude: 100m\n" +
                    "  Icon: 🏠\n" +
                    "  Created by: (!a1b2c3d4)\n" +
                    "  Expires: 2025-12-31 23:59:59",
            ),
        )
    }
}

@PreviewLightDark
@Composable
private fun DebugItemSelectedPreview() {
    AppTheme {
        DebugItem(
            UiMeshLog(
                uuid = "5",
                messageType = "TextMessage",
                formattedReceivedDate = "9/27/20, 8:04:20 PM",
                logMessage = "This is a selected log item with larger font sizes for better readability.",
            ),
            isSelected = true,
        )
    }
}

@PreviewLightDark
@Composable
private fun DebugMenuActionsPreview() {
    AppTheme {
        Row(modifier = Modifier.padding(16.dp)) {
            IconButton(onClick = { /* Preview only */ }, modifier = Modifier.padding(4.dp)) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = stringResource(id = R.string.debug_logs_export),
                )
            }
            IconButton(onClick = { /* Preview only */ }, modifier = Modifier.padding(4.dp)) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(id = R.string.debug_clear))
            }
        }
    }
}

@PreviewLightDark
@Composable
@Suppress("detekt:LongMethod") // big preview
private fun DebugScreenEmptyPreview() {
    AppTheme {
        Surface {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                stickyHeader {
                    Surface(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = "",
                                        onValueChange = {},
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        placeholder = { Text("Search in logs...") },
                                        singleLine = true,
                                    )
                                    TextButton(onClick = {}) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "Filters", style = TextStyle(fontWeight = FontWeight.Bold))
                                            Icon(
                                                imageVector = Icons.TwoTone.FilterAltOff,
                                                contentDescription = stringResource(id = R.string.debug_filters),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Empty state
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No Debug Logs",
                                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                            )
                            Text(
                                text = "Debug logs will appear here when available",
                                style = TextStyle(fontSize = 14.sp, color = Color.Gray),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
@Suppress("detekt:LongMethod") // big preview
private fun DebugScreenWithSampleDataPreview() {
    AppTheme {
        val sampleLogs =
            listOf(
                UiMeshLog(
                    uuid = "1",
                    messageType = "NodeInfo",
                    formattedReceivedDate = "9/27/20, 8:00:58 PM",
                    logMessage =
                    "from: 2885173132\n" +
                        "decoded {\n" +
                        "   position {\n" +
                        "       altitude: 60\n" +
                        "       battery_level: 81\n" +
                        "       latitude_i: 411111136\n" +
                        "       longitude_i: -711111805\n" +
                        "       time: 1600390966\n" +
                        "   }\n" +
                        "}\n" +
                        "hop_limit: 3\n" +
                        "id: 1737414295\n" +
                        "rx_snr: 9.5\n" +
                        "rx_time: 316400569\n" +
                        "to: -1409790708",
                ),
                UiMeshLog(
                    uuid = "2",
                    messageType = "TextMessage",
                    formattedReceivedDate = "9/27/20, 8:01:15 PM",
                    logMessage = "Hello from node (!a1b2c3d4)! How's the weather today?",
                ),
                UiMeshLog(
                    uuid = "3",
                    messageType = "Position",
                    formattedReceivedDate = "9/27/20, 8:02:30 PM",
                    logMessage = "Position update: 40.7128, -74.0060, altitude: 100m, battery: 85%",
                ),
                UiMeshLog(
                    uuid = "4",
                    messageType = "Waypoint",
                    formattedReceivedDate = "9/27/20, 8:03:45 PM",
                    logMessage = "New waypoint created: 'Meeting Point' at 40.7589, -73.9851",
                ),
                UiMeshLog(
                    uuid = "5",
                    messageType = "Error",
                    formattedReceivedDate = "9/27/20, 8:04:20 PM",
                    logMessage = "Connection timeout - retrying in 5 seconds...",
                ),
            )

        // Note: This preview shows the UI structure but won't have actual data
        // since the ViewModel isn't injected in previews
        Surface {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                stickyHeader {
                    Surface(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Debug Screen Preview",
                                style = TextStyle(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            Text(
                                text = "Search and filter controls would appear here",
                                style = TextStyle(fontSize = 12.sp, color = Color.Gray),
                            )
                        }
                    }
                }
                items(sampleLogs) { log -> DebugItem(log = log) }
            }
        }
    }
}

@Composable
fun DebugMenuActions(viewModel: DebugViewModel = hiltViewModel(), modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by viewModel.meshLog.collectAsStateWithLifecycle()
    var showDeleteLogsDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { scope.launch { exportAllLogs(context, logs) } }, modifier = modifier.padding(4.dp)) {
        Icon(
            imageVector = Icons.Outlined.FileDownload,
            contentDescription = stringResource(id = R.string.debug_logs_export),
        )
    }
    IconButton(onClick = { showDeleteLogsDialog = true }, modifier = modifier.padding(4.dp)) {
        Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(id = R.string.debug_clear))
    }
    if (showDeleteLogsDialog) {
        SimpleAlertDialog(
            title = R.string.debug_clear,
            text = R.string.debug_clear_logs_confirm,
            onConfirm = {
                showDeleteLogsDialog = false
                viewModel.deleteAllLogs()
            },
            onDismiss = { showDeleteLogsDialog = false },
        )
    }
}

private suspend fun exportAllLogs(context: Context, logs: List<UiMeshLog>) = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "meshtastic_debug_$timestamp.txt"

        // Get the Downloads directory
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val logFile = File(downloadsDir, fileName)

        // Create the file and write logs
        OutputStreamWriter(FileOutputStream(logFile), StandardCharsets.UTF_8).use { writer ->
            logs.forEach { log ->
                writer.write("${log.formattedReceivedDate} [${log.messageType}]\n")
                writer.write(log.logMessage)
                if (!log.decodedPayload.isNullOrBlank()) {
                    writer.write("\n\nDecoded Payload:\n{")
                    writer.write("\n")
                    writer.write(log.decodedPayload)
                    writer.write("\n}")
                }
                writer.write("\n\n")
            }
        }

        // Notify user of success
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Logs exported to ${logFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: SecurityException) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Permission denied: Cannot write to Downloads folder", Toast.LENGTH_LONG).show()
            warn("Error:SecurityException: " + e.toString())
        }
    } catch (e: IOException) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to write log file: ${e.message}", Toast.LENGTH_LONG).show()
        }
        warn("Error:IOException: " + e.toString())
    }
}

@Composable
private fun DecodedPayloadBlock(
    decodedPayload: String,
    isSelected: Boolean,
    colorScheme: ColorScheme,
    searchText: String = "",
    modifier: Modifier = Modifier,
) {
    val commonTextStyle =
        TextStyle(fontSize = if (isSelected) 10.sp else 8.sp, fontWeight = FontWeight.Bold, color = colorScheme.primary)

    Column(modifier = modifier) {
        Text(
            text = stringResource(id = R.string.debug_decoded_payload),
            style = commonTextStyle,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(text = "{", style = commonTextStyle, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        val annotatedPayload = rememberAnnotatedDecodedPayload(decodedPayload, searchText, colorScheme)
        Text(
            text = annotatedPayload,
            softWrap = true,
            style =
            TextStyle(
                fontSize = if (isSelected) 10.sp else 8.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface.copy(alpha = 0.8f),
            ),
            modifier = Modifier.padding(start = 16.dp, bottom = 0.dp),
        )
        Text(text = "}", style = commonTextStyle, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
    }
}

@Composable
private fun rememberAnnotatedDecodedPayload(
    decodedPayload: String,
    searchText: String,
    colorScheme: ColorScheme,
): AnnotatedString {
    val highlightStyle = SpanStyle(background = colorScheme.primary.copy(alpha = 0.3f), color = colorScheme.onSurface)
    return remember(decodedPayload, searchText) {
        buildAnnotatedString {
            append(decodedPayload)
            if (searchText.isNotEmpty()) {
                searchText.split(" ").forEach { term ->
                    Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(decodedPayload).forEach { match ->
                        addStyle(style = highlightStyle, start = match.range.first, end = match.range.last + 1)
                    }
                }
            }
        }
    }
}
