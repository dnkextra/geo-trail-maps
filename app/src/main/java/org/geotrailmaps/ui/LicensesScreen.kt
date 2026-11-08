package org.geotrailmaps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The license documents bundled under assets/legal/ by the copyLegalNotices build task. */
private val documents = listOf(
    "Geo Trail Maps license (MIT)" to "LICENSE",
    "Third-party notices" to "THIRD_PARTY_NOTICES.md",
    "Apache License 2.0" to "Apache-2.0.txt",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licenses") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            for ((title, file) in documents) {
                val open = expanded == file
                ListItem(
                    headlineContent = { Text(title) },
                    trailingContent = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                    modifier = Modifier.clickable { expanded = if (open) null else file },
                )
                if (open) LicenseText(file)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LicenseText(file: String) {
    val context = LocalContext.current
    val text = remember(file) {
        reflow(context.assets.open("legal/$file").bufferedReader().use { it.readText() })
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    )
}

/**
 * Makes hard-wrapped plain text and the simple Markdown in the notices readable on a phone:
 * joins wrapped lines into paragraphs, bolds headings, turns table rows and list items into
 * bullets, and keeps a link's text (plus its URL when it's a web address).
 */
private fun reflow(source: String): AnnotatedString {
    val link = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    fun inline(s: String) = s
        .replace(link) { m -> m.groupValues[2].let { if (it.startsWith("http")) "${m.groupValues[1]} ($it)" else m.groupValues[1] } }
        .replace("**", "").replace("`", "")

    val blocks = mutableListOf<Pair<Boolean, String>>() // (heading, text)
    var paragraph = ""
    fun flush() { if (paragraph.isNotEmpty()) blocks += false to paragraph; paragraph = "" }
    for (raw in source.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> { flush(); blocks += true to inline(line.trimStart('#').trim()) }
            line.startsWith("|") -> {
                flush()
                val cells = line.trim('|').split("|").map { inline(it.trim()) }
                if (cells.all { it.matches(Regex("-+")) }) blocks.removeLastOrNull() // drop the header row
                else blocks += false to "• " + cells.joinToString(" — ")
            }
            line.startsWith("- ") -> { flush(); paragraph = "• " + inline(line.removePrefix("- ")) }
            else -> paragraph = if (paragraph.isEmpty()) inline(line) else "$paragraph ${inline(line)}"
        }
    }
    flush()
    return buildAnnotatedString {
        blocks.forEachIndexed { i, (heading, text) ->
            if (i > 0) append("\n\n")
            if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) } else append(text)
        }
    }
}
