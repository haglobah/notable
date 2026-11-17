package com.ethran.notable.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ethran.notable.NotableApp
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NCodeBlock
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class CrashLog(val name: String, val content: String)

/**
 * Debug-tab section that surfaces the local crash files ([NotableApp] writes one per crash) so a
 * user can read and **share** them when asked, instead of them being reachable only over adb.
 * ShipBook is the automatic remote channel; this is the fallback for the cases ShipBook can't cover
 * (network/heap dead, or a crash before ShipBook starts).
 */
@Composable
fun CrashLogsSettings() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Bumping this re-runs the loader (after a Clear).
    var reload by remember { mutableIntStateOf(0) }

    val logs by produceState(initialValue = emptyList<CrashLog>(), reload) {
        value = withContext(Dispatchers.IO) { loadCrashLogs(context) }
    }

    NSection(
        title = "Crash logs",
        description = if (logs.isEmpty()) null else "${logs.size} ${if (logs.size == 1) "file" else "files"}, newest first",
    ) {
        if (logs.isEmpty()) {
            Text(
                text = "Nothing has crashed since the last clear.",
                style = NotableTheme.type.meta,
                color = NotableTheme.colors.secondary,
            )
            return@NSection
        }

        val combined = remember(logs) { combineForExport(logs) }

        NCodeBlock(combined, maxHeight = 240.dp)

        Row(
            Modifier.padding(top = NotableSpace.md),
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
        ) {
            NButton("Copy", onClick = {
                clipboard.setText(AnnotatedString(combined))
                Toast.makeText(context, "Copied crash logs", Toast.LENGTH_SHORT).show()
            }, icon = FeatherIcons.Copy)

            NButton("Share", onClick = { shareText(context, combined) }, icon = FeatherIcons.Share2)

            NButton("Clear", onClick = {
                clearCrashLogs(context)
                reload++
            }, style = NButtonStyle.Danger, icon = FeatherIcons.Trash2)
        }
    }
}

/** Load + combine all crash files into one shareable/copyable string. Reusable outside Compose
 *  (e.g. the crash-loop hint's "Copy logs" action). Small text files, so this is cheap. */
internal fun crashLogsAsText(context: Context): String = combineForExport(loadCrashLogs(context))

/** Newest-first; small text files, so reading them here is cheap (and this runs off the main thread). */
private fun loadCrashLogs(context: Context): List<CrashLog> {
    val dir = File(context.filesDir, NotableApp.CRASH_DIR)
    val files = dir.listFiles()?.sortedByDescending { it.name } ?: return emptyList()
    return files.map { file ->
        val content = runCatching { file.readText() }.getOrElse { "<unreadable: ${it.message}>" }
        CrashLog(file.name, content)
    }
}

private fun combineForExport(logs: List<CrashLog>): String =
    logs.joinToString("\n\n") { "──── ${it.name} ────\n${it.content}" }

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Notable crash logs")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share crash logs"))
}

private fun clearCrashLogs(context: Context) {
    runCatching {
        File(context.filesDir, NotableApp.CRASH_DIR).listFiles()?.forEach { it.delete() }
    }
}
