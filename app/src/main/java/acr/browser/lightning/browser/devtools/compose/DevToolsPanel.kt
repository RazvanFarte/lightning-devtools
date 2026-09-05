package acr.browser.lightning.browser.devtools.compose

import acr.browser.lightning.browser.devtools.DevToolsController
import acr.browser.lightning.browser.devtools.console.ConsoleMessage
import acr.browser.lightning.browser.devtools.net.RecordedEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The on-device DevTools panel.
 *
 * Rendered as native Compose rather than as a web page inside a second WebView. That choice is
 * deliberate: a DOM-based inspector on a phone has to reimplement scrolling, and the usual result
 * is that scroll gestures get misread as taps. A [LazyColumn] scrolls the way the platform does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsPanel(controller: DevToolsController) {
    val isOpen by controller.isOpen.collectAsState()
    if (!isOpen) return

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }

    val exportResult by controller.lastExport.collectAsState()
    LaunchedEffect(exportResult) {
        val message = when (val result = exportResult) {
            is DevToolsController.ExportResult.Saved ->
                "Saved ${result.fileName} to Downloads (${result.entryCount} requests)"

            DevToolsController.ExportResult.Empty ->
                "Nothing recorded yet - reload the page with recording on"

            DevToolsController.ExportResult.Failed -> "Could not write the HAR file"
            null -> null
        }
        message?.let {
            snackbarHostState.showSnackbar(it)
            controller.consumeExportResult()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("DevTools") },
                    actions = {
                        TextButton(onClick = { controller.close() }) { Text("Close") }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Network") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Console") }
                    )
                }
                when (selectedTab) {
                    0 -> NetworkTab(
                        controller = controller,
                        onExport = { controller.exportHar(context) },
                        onShare = { controller.shareHar(context) }
                    )

                    else -> ConsoleTab(controller)
                }
            }
        }
    }
}

@Composable
private fun NetworkTab(
    controller: DevToolsController,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    val entries by controller.networkRecorder.entries.collectAsState()
    val isRecording by controller.networkRecorder.isRecording.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val visible = remember(entries, filter) {
        if (filter == "all") entries else entries.filter { it.resourceType == filter }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = { controller.toggleRecording() }) {
                if (isRecording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(if (isRecording) " Stop" else "Record")
            }
            TextButton(onClick = { controller.clear() }) { Text("Clear") }
            TextButton(onClick = onExport) { Text("Export HAR") }
            TextButton(onClick = onShare) { Text("Share") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RESOURCE_FILTERS.forEach { type ->
                FilterChip(
                    selected = filter == type,
                    onClick = { filter = type },
                    label = { Text(type, fontSize = 12.sp) }
                )
            }
        }

        Text(
            text = "${visible.size} requests" +
                if (isRecording) "" else "  -  recording off",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        HorizontalDivider()

        if (visible.isEmpty()) {
            EmptyState(
                if (isRecording) {
                    "Recording. Load a page to capture requests."
                } else {
                    "Press Record, then load a page."
                }
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { entry ->
                    NetworkRow(
                        entry = entry,
                        isExpanded = expandedId == entry.id,
                        onClick = { expandedId = if (expandedId == entry.id) null else entry.id }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(entry: RecordedEntry, isExpanded: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(entry)
            Text(
                text = entry.url.substringAfterLast('/').ifBlank { entry.url },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            Text(
                text = "${entry.totalTimeMillis.toInt()} ms",
                style = MaterialTheme.typography.labelSmall
            )
        }
        Text(
            text = "${entry.method}  ${entry.resourceType}  ${entry.url}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (isExpanded) {
            NetworkDetail(entry)
        }
    }
}

@Composable
private fun NetworkDetail(entry: RecordedEntry) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        DetailSection("General") {
            MonoText("${entry.method} ${entry.url}")
            MonoText("Status: ${entry.status} ${entry.statusText}")
            MonoText("Protocol: ${entry.protocol.ifBlank { "unknown" }}")
            entry.serverIpAddress?.let { MonoText("Server IP: $it") }
            entry.error?.let { MonoText("Error: $it") }
        }
        DetailSection("Timing") {
            val t = entry.timings
            // -1 means the phase did not occur, so it is shown as a dash rather than as zero.
            MonoText("blocked ${t.blocked.asMillis()}   dns ${t.dns.asMillis()}")
            MonoText("connect ${t.connect.asMillis()}   ssl ${t.ssl.asMillis()}")
            MonoText("send ${t.send.asMillis()}   wait ${t.wait.asMillis()}   receive ${t.receive.asMillis()}")
        }
        if (entry.requestHeaders.isNotEmpty()) {
            DetailSection("Request headers") {
                entry.requestHeaders.forEach { MonoText("${it.name}: ${it.value}") }
            }
        }
        entry.requestBody?.let {
            DetailSection("Request payload") { MonoText(it.take(BODY_PREVIEW_CHARS)) }
        }
        if (entry.responseHeaders.isNotEmpty()) {
            DetailSection("Response headers") {
                entry.responseHeaders.forEach { MonoText("${it.name}: ${it.value}") }
            }
        }
        entry.bodyText?.let {
            DetailSection(
                "Response" + if (entry.bodyTruncated) " (truncated)" else ""
            ) {
                MonoText(
                    if (entry.bodyEncoding == "base64") {
                        "[binary, ${entry.decodedBodySize} bytes]"
                    } else {
                        it.take(BODY_PREVIEW_CHARS)
                    }
                )
            }
        }
    }
}

@Composable
private fun ConsoleTab(controller: DevToolsController) {
    val messages by controller.consoleLog.messages.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { controller.consoleLog.clear() }) { Text("Clear") }
        }
        HorizontalDivider()
        if (messages.isEmpty()) {
            EmptyState("No console output captured yet.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(messages) { message ->
                    ConsoleRow(message)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConsoleRow(message: ConsoleMessage) {
    val color = when (message.level) {
        ConsoleMessage.Level.ERROR -> MaterialTheme.colorScheme.error
        ConsoleMessage.Level.WARN -> WARN_COLOR
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            text = "${TIME_FORMAT.format(Date(message.timestampMillis))}  " +
                message.level.name.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = message.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatusBadge(entry: RecordedEntry) {
    val color = when {
        entry.error != null -> MaterialTheme.colorScheme.error
        entry.status >= 500 -> MaterialTheme.colorScheme.error
        entry.status >= 400 -> WARN_COLOR
        entry.status >= 300 -> MaterialTheme.colorScheme.tertiary
        entry.status > 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Text(
        text = if (entry.status > 0) entry.status.toString() else "err",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}

private fun Double.asMillis(): String = if (this < 0) "-" else "${toInt()}ms"

private const val BODY_PREVIEW_CHARS = 4000

private val WARN_COLOR = Color(0xFFB26A00)

private val RESOURCE_FILTERS = listOf(
    "all", "document", "xhr", "script", "stylesheet", "image", "font", "media", "other"
)

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
