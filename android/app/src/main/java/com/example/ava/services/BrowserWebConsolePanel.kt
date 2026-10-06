package com.example.ava.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.example.ava.ui.AvaToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.glass.liquidGlass
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One console line — mirrors kiosk-satellite [ConsoleEntry]. */
data class BrowserConsoleEntry(
    val id: Long,
    val timeMs: Long,
    val level: String,
    val message: String,
)

/**
 * Bottom-docked JS console (kiosk [WebConsolePanel] layout): live levels, copy/share/clear,
 * and a REPL that evals in the page.
 *
 * The expand button next to Send swaps the log area for a full-size multi-line
 * code editor and asks the host (via [onExpandedChange]) to grow the panel to
 * the whole overlay — long snippets stop scrolling inside a one-line strip.
 * Send auto-collapses so the eval result is immediately visible in the log.
 *
 * Surfaces follow Ava [MaterialTheme]; accent matches browser overlay
 * ([AccentBrown] dark / [AccentBlue] light) — not Material primaryDark.
 */
@Composable
fun BrowserWebConsolePanel(
    entries: List<BrowserConsoleEntry>,
    revision: Int,
    isDarkMode: Boolean,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    /** Host hook: grow the docked panel to full height while the editor is open. */
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var editorExpanded by remember { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val surface = colors.surfaceContainer.copy(alpha = 0.96f)
    val onSurface = colors.onSurface
    val outline = colors.outlineVariant
    val muted = colors.onSurfaceVariant
    val accent = if (isDarkMode) AccentBrown else AccentBlue
    val error = colors.error
    val fieldBackground = onSurface.copy(alpha = 0.06f)
    val lineSp = 14.sp
    val lineDp = with(LocalDensity.current) { lineSp.toDp() }
    val lineStyle = TextStyle(
        fontSize = lineSp,
        lineHeight = lineSp,
        fontFamily = FontFamily.Monospace,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
    val glass by rememberLiquidGlassState()
    // Bottom-docked sheet: only the top corners round, the bottom edge stays flush
    // with the screen so the panel reads as grown out of the dock.
    val panelShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    fun setExpanded(expanded: Boolean) {
        editorExpanded = expanded
        onExpandedChange(expanded)
    }

    fun send() {
        val code = input.trim()
        if (code.isEmpty()) return
        input = ""
        if (editorExpanded) setExpanded(false)
        onRun(code)
    }

    LaunchedEffect(revision, entries.size) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    fun exportText(): String = entries.joinToString("\n") { e ->
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(e.timeMs))
        "$iso [${e.level}] ${e.message}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .then(
                if (glass.enabled) {
                    Modifier.liquidGlass(glass, panelShape, isDarkMode, surface)
                } else {
                    Modifier.background(surface, panelShape)
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.browser_sidebar_web_console),
                color = onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("console", exportText()))
                    AvaToast.show(
                        context,
                        context.getString(R.string.browser_web_console_copied),
                    )
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.browser_sidebar_web_console))
                    putExtra(Intent.EXTRA_TEXT, exportText())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Block, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = outline)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (editorExpanded) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(
                        color = onSurface,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .background(fieldBackground, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.browser_web_console_hint),
                                    color = muted,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            inner()
                        }
                    },
                )
            } else if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.browser_web_console_empty),
                    color = muted,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timeMs))
                        val color = levelColor(entry.level, onSurface, accent, muted, error)
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = muted)) { append("$time ") }
                                withStyle(SpanStyle(color = color)) { append(entry.message) }
                            },
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editorExpanded) {
                // The editor above owns the text; this strip is just the actions.
                Text(
                    text = stringResource(R.string.browser_web_console_hint),
                    color = muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(fieldBackground, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.height(lineDp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = ">",
                            style = lineStyle.copy(color = accent),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(lineDp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browser_web_console_hint),
                                style = lineStyle.copy(color = muted),
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = lineStyle.copy(color = onSurface),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            IconButton(onClick = { setExpanded(!editorExpanded) }) {
                Icon(
                    imageVector = if (editorExpanded) {
                        Icons.Outlined.FullscreenExit
                    } else {
                        Icons.Outlined.Fullscreen
                    },
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = { send() }) {
                Icon(Icons.Outlined.Send, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun levelColor(
    level: String,
    onSurface: Color,
    accent: Color,
    muted: Color,
    error: Color,
): Color = when (level) {
    "error" -> error
    "warn" -> Color(0xFFE6C07A)
    "debug" -> muted
    "tip" -> accent
    "cmd" -> accent
    else -> onSurface
}
