package com.remotecontrolplus.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.core.Diagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The diagnostics log, with a share action so it can be sent as text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by Diagnostics.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { Diagnostics.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Remote Control Plus diagnostics")
                            putExtra(Intent.EXTRA_TEXT, Diagnostics.export())
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share log")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Text(
                "Nothing logged yet. Scan for TVs, pair or press a button, then come back here.",
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(entries) { _, e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "${fmt.format(Date(e.time))}  ${e.tag}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (e.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(e.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    e.error?.let {
                        Text(
                            it.lineSequence().take(4).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
