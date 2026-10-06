package com.example.ava.ui.screens.settings

import android.net.Uri
import com.example.ava.ui.AvaToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.WakeMode
import com.example.ava.settings.WakeWordEngine
import com.example.ava.ui.safePopBackStack
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.SettingsCardInnerHorizontalPadding
import com.example.ava.ui.screens.settings.components.VoiceDetailScaffold
import com.example.ava.ui.screens.settings.components.VoiceStatsFocus
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.wakewordlibrary.WakeWordImportResult
import com.example.ava.wakewordlibrary.WakeWordLibraryEntry
import kotlinx.coroutines.launch
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon

@Composable
fun WakeWordLibraryScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val entries by viewModel.wakeWordLibraryEntries.collectAsStateWithLifecycle()
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val currentEngine = microphoneState?.wakeWordEngine ?: WakeWordEngine.MICRO_WAKE_WORD
    val wakeEngineDisabled = microphoneState?.wakeMode == WakeMode.BUTTON

    LaunchedEffect(wakeEngineDisabled) {
        if (wakeEngineDisabled) navController.safePopBackStack()
    }

    val microCatalog by viewModel.microWakeWordCatalog.collectAsStateWithLifecycle()
    val microDownloadingId by viewModel.microCatalogDownloadingId.collectAsStateWithLifecycle()
    val microDownloadPct by viewModel.microCatalogDownloadPercent.collectAsStateWithLifecycle()
    val microLoadState by viewModel.microCatalogLoadState.collectAsStateWithLifecycle()
    val vsCatalog by viewModel.vsWakeWordCommunityCatalog.collectAsStateWithLifecycle()
    val vsDownloadingId by viewModel.vsWakeWordCatalogDownloadingId.collectAsStateWithLifecycle()
    val vsDownloadPct by viewModel.vsWakeWordCatalogDownloadPercent.collectAsStateWithLifecycle()
    val vsLoadState by viewModel.vsWakeWordCommunityLoadState.collectAsStateWithLifecycle()

    var pendingModel by remember { mutableStateOf<Pair<String, WakeWordEngine>?>(null) }

    LaunchedEffect(currentEngine) {
        when (currentEngine) {
            WakeWordEngine.MICRO_WAKE_WORD -> viewModel.refreshMicroWakeWordCatalog()
            WakeWordEngine.OPEN_WAKE_WORD -> viewModel.refreshVsWakeWordCatalog()
        }
    }

    fun persistReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
    }

    fun toastImportResult(result: WakeWordImportResult) {
        val message = when (result) {
            is WakeWordImportResult.Complete -> when {
                result.ids.size > 1 ->
                    context.getString(R.string.wake_word_library_import_success_count, result.ids.size)
                result.warnings.isNotEmpty() ->
                    context.getString(R.string.wake_word_library_import_partial, result.warnings.size)
                else ->
                    context.getString(R.string.wake_word_library_import_success)
            }
            is WakeWordImportResult.NeedsModel ->
                context.getString(R.string.wake_word_library_import_needs_model)
            is WakeWordImportResult.Failed ->
                importErrorMessage(context, result.message)
        }
        AvaToast.show(context, message)
    }

    val modelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = pendingModel
        pendingModel = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        persistReadPermission(uri)
        coroutineScope.launch {
            val result = viewModel.importWakeWordModel(uri, target.first, target.second)
            if (result is WakeWordImportResult.Complete) {
                VoiceSatelliteService.getInstance()?.reloadWakeWordLibrary()
            }
            toastImportResult(result)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistReadPermission(uri)
        coroutineScope.launch {
            when (val result = viewModel.importWakeWordFile(uri)) {
                is WakeWordImportResult.Complete -> {
                    VoiceSatelliteService.getInstance()?.reloadWakeWordLibrary()
                    toastImportResult(result)
                }
                is WakeWordImportResult.NeedsModel -> toastImportResult(result)
                is WakeWordImportResult.Failed -> toastImportResult(result)
            }
        }
    }

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.wake_word_library_title),
        focus = VoiceStatsFocus.WakeLibrary,
    ) {
        // -- Import row --
        item {
            SimpleCard {
                WakeWordImportRow(
                    title = stringResource(R.string.wake_word_library_import),
                    description = stringResource(R.string.wake_word_library_import_desc),
                    onClick = { fileLauncher.launch("*/*") },
                )
            }
        }

        // -- Local section: header is always on screen, cards fill in when scanned --
        item {
            SectionLabel(stringResource(R.string.wake_word_catalog_section_local))
        }
        if (entries.isNotEmpty()) {
            entries.forEach { entry ->
                item(key = "wake_word_${entry.engine.name}_${entry.id}") {
                    WakeWordLibraryEntryCard(
                        entry = entry,
                        currentEngine = currentEngine,
                        onAddModel = {
                            pendingModel = entry.id to entry.engine
                            modelLauncher.launch("*/*")
                        },
                        onSetWakeWord = {
                            coroutineScope.launch {
                                if (entry.engine != currentEngine) {
                                    viewModel.saveWakeWordEngine(entry.engine)
                                }
                                viewModel.saveWakeWord(entry.id)
                                navController.safePopBackStack()
                            }
                        },
                        onDelete = {
                            coroutineScope.launch {
                                viewModel.deleteWakeWordLibraryEntry(entry.id, entry.engine)
                                VoiceSatelliteService.getInstance()?.reloadWakeWordLibrary()
                            }
                        },
                    )
                }
            }
        } else {
            item {
                SettingsHelpBodyText(
                    text = stringResource(R.string.wake_word_library_empty),
                    modifier = Modifier.padding(
                        start = SettingsCardInnerHorizontalPadding,
                        top = 4.dp,
                        bottom = 16.dp,
                    ),
                )
            }
        }

        // -- Divider before online section --
        item {
            HorizontalDivider(
                color = Color(0xFFE5E7EB).copy(alpha = 0.4f),
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = if (entries.isNotEmpty()) 18.dp else 8.dp,
                    bottom = 8.dp,
                ),
            )
        }

        // -- Online section stays mounted; catalog rows replace the skeleton when ready --
        when (currentEngine) {
            WakeWordEngine.MICRO_WAKE_WORD -> {
                val visibleMicro = microCatalog.filter {
                    it.id == microDownloadingId || !viewModel.isMicroCatalogModelInstalled(it.id)
                }
                item(key = "online_micro_section") {
                    OnlineCatalogSection(
                        engineLabel = stringResource(R.string.option_wake_word_engine_micro),
                        catalog = visibleMicro.map { OnlineCatalogItem(it.id, it.name, it.author, it.sourceUrl) },
                        downloadingId = microDownloadingId,
                        downloadPercent = microDownloadPct,
                        loadState = microLoadState,
                        onDownload = { id ->
                            coroutineScope.launch {
                                val ok = viewModel.downloadMicroCatalogModel(id)
                                AvaToast.show(
                                    context,
                                    context.getString(
                                        if (ok) R.string.wake_word_catalog_download_success
                                        else R.string.wake_word_catalog_download_failed,
                                    ),
                                )
                                if (ok) VoiceSatelliteService.getInstance()?.reloadWakeWordLibrary()
                            }
                        },
                    )
                }
            }
            WakeWordEngine.OPEN_WAKE_WORD -> {
                val visibleOpen = vsCatalog.filter {
                    it.id == vsDownloadingId || !viewModel.isVsCatalogModelInstalled(it.id)
                }
                item(key = "online_vs_section") {
                    OnlineCatalogSection(
                        engineLabel = stringResource(R.string.option_wake_word_engine_vs),
                        catalog = visibleOpen.map { OnlineCatalogItem(it.id, it.name, it.author, it.website) },
                        downloadingId = vsDownloadingId,
                        downloadPercent = vsDownloadPct,
                        loadState = vsLoadState,
                        onDownload = { id ->
                            coroutineScope.launch {
                                val ok = viewModel.downloadVsCatalogModel(id)
                                AvaToast.show(
                                    context,
                                    context.getString(
                                        if (ok) R.string.wake_word_catalog_download_success
                                        else R.string.wake_word_catalog_download_failed,
                                    ),
                                )
                                if (ok) VoiceSatelliteService.getInstance()?.reloadWakeWordLibrary()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------- Section label ----------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = settingsBodyTextSize(base = 14f),
        fontWeight = FontWeight(650),
        color = getSettingsDescriptionColor(),
        modifier = Modifier.padding(
            start = SettingsCardInnerHorizontalPadding,
            top = 18.dp,
            bottom = 4.dp,
        ),
    )
}

// ---------- Import row ----------

@Composable
private fun WakeWordImportRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.mdi_shape_plus),
            contentDescription = null,
            tint = getAccentColor(),
            modifier = Modifier
                .padding(start = 12.dp)
                .size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = settingsTitleTextSize(),
                color = getAccentColor(),
                fontWeight = FontWeight.Medium,
            )
            CollapsibleDescriptionText(
                text = description,
                collapsedLines = 3,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                fadeToColor = getDialogBackground(),
            )
        }
        SettingsChevronIcon(tint = Color(0xFF94A3B8),
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

private fun importErrorMessage(context: android.content.Context, code: String): String = when (code) {
    "pick_json_first" -> context.getString(R.string.wake_word_library_error_pick_json_first)
    "unsupported_file" -> context.getString(R.string.wake_word_library_error_unsupported_file)
    "invalid_name" -> context.getString(R.string.wake_word_library_error_invalid_name)
    "read_failed" -> context.getString(R.string.wake_word_library_import_failed)
    "expected_tflite" -> context.getString(R.string.wake_word_library_error_expected_tflite)
    "expected_onnx" -> context.getString(R.string.wake_word_library_error_expected_onnx)
    "invalid_onnx" -> context.getString(R.string.wake_word_library_error_expected_onnx)
    "missing_json" -> context.getString(R.string.wake_word_library_error_missing_json)
    "invalid_json" -> context.getString(R.string.wake_word_library_error_invalid_json)
    "invalid manifest" -> context.getString(R.string.wake_word_library_error_invalid_json)
    "stop classifier not supported here" -> context.getString(R.string.wake_word_library_error_stop_classifier)
    "zip_empty" -> context.getString(R.string.wake_word_library_error_zip_empty)
    else -> context.getString(R.string.wake_word_library_import_failed)
}

// ---------- Local entry card ----------

@Composable
private fun WakeWordLibraryEntryCard(
    entry: WakeWordLibraryEntry,
    currentEngine: WakeWordEngine,
    onAddModel: () -> Unit,
    onSetWakeWord: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
            title = {
                Text(
                    text = stringResource(R.string.wake_word_library_delete),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor(),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.wake_word_library_delete_confirm, entry.displayName),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(
                        text = stringResource(R.string.wake_word_library_delete),
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        fontSize = settingsTitleTextSize(),
                        color = getSettingsDescriptionColor(),
                    )
                }
            },
        )
    }

    SimpleCard {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        text = entry.displayName,
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.SemiBold,
                        color = getTitleColor(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (entry.engine) {
                            WakeWordEngine.MICRO_WAKE_WORD ->
                                stringResource(R.string.option_wake_word_engine_micro)
                            WakeWordEngine.OPEN_WAKE_WORD ->
                                stringResource(R.string.option_wake_word_engine_vs)
                        },
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                WakeWordStatusBadge(entry = entry, currentEngine = currentEngine)
            }
            HorizontalDivider(
                color = Color(0xFFE5E7EB).copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!entry.ready) {
                    Text(
                        text = stringResource(R.string.wake_word_library_add_model),
                        fontSize = settingsBodyTextSize(),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(onClick = onAddModel),
                    )
                    Spacer(modifier = Modifier.width(18.dp))
                    Text("|", fontSize = settingsBodyTextSize(), color = Color(0xFFD1D5DB))
                    Spacer(modifier = Modifier.width(18.dp))
                } else if (entry.engine == currentEngine) {
                    Text(
                        text = stringResource(R.string.wake_word_library_set_wake_word),
                        fontSize = settingsBodyTextSize(),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(onClick = onSetWakeWord),
                    )
                    Spacer(modifier = Modifier.width(18.dp))
                    Text("|", fontSize = settingsBodyTextSize(), color = Color(0xFFD1D5DB))
                    Spacer(modifier = Modifier.width(18.dp))
                }
                Text(
                    text = stringResource(R.string.wake_word_library_delete),
                    fontSize = settingsBodyTextSize(),
                    color = getAccentColor(),
                    modifier = Modifier.clickable { showDeleteDialog = true },
                )
            }
        }
    }
}

@Composable
private fun WakeWordStatusBadge(
    entry: WakeWordLibraryEntry,
    currentEngine: WakeWordEngine,
) {
    val (labelRes, borderColor, textColor) = when {
        !entry.ready -> Triple(
            R.string.wake_word_library_status_missing_model,
            Color(0xFFF59E0B),
            Color(0xFFD97706),
        )
        entry.engine != currentEngine -> Triple(
            R.string.wake_word_library_status_engine_mismatch,
            getSettingsDescriptionColor(),
            getSettingsDescriptionColor(),
        )
        else -> Triple(
            R.string.wake_word_library_status_ready,
            Color(0xFF22C55E),
            Color(0xFF15803D),
        )
    }
    Box(
        modifier = Modifier
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
