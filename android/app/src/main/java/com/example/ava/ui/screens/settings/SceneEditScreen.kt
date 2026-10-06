package com.example.ava.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.notifications.FontAwesomeHelper
import com.example.ava.notifications.NotificationScenes
import com.example.ava.notifications.ScenePalette
import com.example.ava.notifications.SceneTemplateResolver
import com.example.ava.services.NotificationOverlayService
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.LocalSceneEntry
import com.example.ava.settings.localScenesStore
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.ActionDialog
import com.example.ava.ui.screens.settings.components.CustomAccentColorDialog
import com.example.ava.ui.screens.settings.components.CustomColorSwatch
import com.example.ava.ui.screens.settings.components.DialogScope
import com.example.ava.ui.screens.settings.components.PresetColorSwatch
import com.example.ava.ui.screens.settings.components.SettingsBottomDockCapsule
import com.example.ava.ui.screens.settings.components.SettingsBottomDockClearance
import com.example.ava.ui.screens.settings.components.SettingsBottomDockLandscapeMaxWidth
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.rememberHandleSheetFill
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First screen of the icon picker; more rows append only after scrolling to the end. */
private const val IconPickerPageSize = 40

@Composable
private fun sceneEditDarkMode(): Boolean {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    return isDarkMode
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditScreen(
    navController: NavController,
    sceneIdArg: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { context.localScenesStore }
    val isLandscape = LocalConfiguration.current.screenWidthDp >
        LocalConfiguration.current.screenHeightDp
    val accent = getAccentColor()
    val isDark = sceneEditDarkMode()
    val labelColor = if (isDark) Color.White else Color(0xFF111827)
    val cardBg = if (isDark) Color(0xFF1F1F22) else Color.White
    val slotBg = if (isDark) Color(0xFF26262A) else Color(0xFFF3F4F6)
    val screenBg = if (isDark) Color.Black else Color(0xFFF9FAFB)
    val sub = getSettingsDescriptionColor()

    val isNew = sceneIdArg == "new"
    var entry by remember { mutableStateOf(LocalSceneEntry.newDraft()) }
    var ready by remember { mutableStateOf(false) }
    var iconQuery by remember { mutableStateOf("") }
    var showEntitySheet by remember { mutableStateOf(false) }
    val haPickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()
    var insertTarget by remember { mutableStateOf("title") }
    var showDupWarn by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var isOverlay by remember { mutableStateOf(false) }
    /** True after Save / Restore — skip dispose rollback of ephemeral preview. */
    var committed by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    /** Preview used setPreviewOverride — keep it fresh while editing. */
    var previewOverrideActive by remember { mutableStateOf(false) }
    val restoreDialog = remember { DialogScope() }
    val restoreOpen by restoreDialog.isDialogOpen.collectAsStateWithLifecycle()
    val committedLatest = rememberUpdatedState(committed)
    val dirtyLatest = rememberUpdatedState(dirty)
    val isNewLatest = rememberUpdatedState(isNew)

    val faTypeface = remember { FontAwesomeHelper.loadFont(context) }
    val iconGridState = rememberLazyGridState()
    var iconPage by remember { mutableIntStateOf(1) }
    val filteredIcons = remember(iconQuery) {
        val q = iconQuery.trim().lowercase()
        if (q.isEmpty()) FontAwesomeHelper.ICONS
        else FontAwesomeHelper.ICONS.filter { it.first.contains(q) }
    }
    val visibleIcons = remember(filteredIcons, iconPage) {
        val cap = iconPage * IconPickerPageSize
        if (filteredIcons.size <= cap) filteredIcons
        else filteredIcons.subList(0, cap)
    }
    LaunchedEffect(iconQuery) {
        iconPage = 1
        iconGridState.scrollToItem(0)
    }
    LaunchedEffect(iconGridState, visibleIcons.size, filteredIcons.size) {
        if (visibleIcons.size >= filteredIcons.size) return@LaunchedEffect
        snapshotFlow {
            val info = iconGridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 1 && info.totalItemsCount > 0
        }.collect { atEnd ->
            if (atEnd && visibleIcons.size < filteredIcons.size) {
                iconPage += 1
            }
        }
    }

    LaunchedEffect(sceneIdArg) {
        if (isNew) {
            // Memory-only draft — do not touch DataStore / HA entity set until Save.
            val draft = LocalSceneEntry.newDraft()
            entry = draft
            draftId = draft.id
            isOverlay = false
            ready = true
        } else {
            val existing = store.getById(sceneIdArg)
            if (existing != null) {
                entry = existing
                isOverlay = !NotificationScenes.isLocalScene(sceneIdArg)
                ready = true
            } else {
                val live = NotificationScenes.getSceneById(sceneIdArg)
                if (live != null) {
                    entry = LocalSceneEntry.from(live)
                    isOverlay = !NotificationScenes.isLocalScene(sceneIdArg)
                    ready = true
                } else {
                    navController.popBackStack()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Never leave editor drafts in the live scene set / HA subscriptions.
            NotificationScenes.clearPreviewOverride()
            if (!committedLatest.value && (isNewLatest.value || dirtyLatest.value)) {
                scope.launch {
                    NotificationScenes.setLocalScenes(
                        store.list().map { it.toNotificationScene() },
                        notify = false,
                    )
                }
            }
        }
    }

    fun persist(next: LocalSceneEntry) {
        entry = next
        dirty = true
        // Memory-only until Save. Never DataStore / never ALL_SCENES / never HA resubscribe.
        if (previewOverrideActive) {
            NotificationScenes.setPreviewOverride(next.toNotificationScene())
        }
        val titles = NotificationScenes.ALL_SCENES
            .filter { it.id != next.id }
            .map { it.title }
        showDupWarn = next.title.isNotBlank() && titles.contains(next.title)
    }

    fun applyColor(hex: String) {
        val derived = ScenePalette.deriveTheme(hex)
        persist(
            entry.copy(
                themeColors = derived.themeColors,
                iconColor = derived.iconColor,
                beamColor = derived.beamColor,
                dividerColor = derived.dividerColor,
                dotColor = derived.dotColor,
            )
        )
    }

    fun resolvePreview(text: String): String {
        if (!SceneTemplateResolver.hasPlaceholders(text)) return text
        val svc = VoiceSatelliteService.getInstance()
        val states = svc?.getSceneEntityStates().orEmpty()
        val units = svc?.getSceneEntityUnits().orEmpty()
        val attrs = svc?.getSceneEntityAttributes().orEmpty()
        return SceneTemplateResolver.resolve(text) { eid, haAttr ->
            when (haAttr) {
                "" -> states[eid]
                "unit_of_measurement" -> units[eid]
                else -> attrs[eid]?.get(haAttr)
            }
        }
    }

    fun previewScene() {
        if (entry.title.isBlank()) return
        // Override is lookup-only — does not merge into ALL_SCENES or ping HA.
        previewOverrideActive = true
        NotificationScenes.setPreviewOverride(entry.toNotificationScene())
        NotificationOverlayService.previewScene(context, entry.id)
    }

    /** Persist to DataStore (pinned to top of user section), then leave. */
    fun saveAndClose() {
        if (entry.title.isBlank()) return
        scope.launch {
            store.upsert(entry)
            NotificationScenes.clearPreviewOverride()
            previewOverrideActive = false
            NotificationScenes.setLocalScenes(store.list().map { it.toNotificationScene() })
            committed = true
            dirty = false
            draftId = null
            withContext(Dispatchers.Main) { navController.popBackStack() }
        }
    }

    ImmersiveMode(isLandscape = isLandscape)

    Scaffold(
        contentWindowInsets = avaContentWindowInsets(isLandscape),
        topBar = {
            SettingsHeaderBar(
                title = if (isNew) stringResource(R.string.notif_scene_new)
                else stringResource(R.string.notif_scene_edit),
                titleColor = labelColor,
                containerColor = screenBg,
                onBack = { navController.popBackStack() },
                isLandscape = isLandscape,
                windowInsets = avaTopBarWindowInsets(isLandscape),
            )
        },
        containerColor = screenBg,
    ) { padding ->
        if (!ready) return@Scaffold
        // Landscape: keep form + dock to a comfortable column so Preview/Save
        // don't stretch edge-to-edge on wide panels. widthIn must wrap fillMaxWidth.
        val landscapeContentMax = SettingsBottomDockLandscapeMaxWidth
        val contentWidthMod = if (isLandscape) {
            Modifier
                .widthIn(max = landscapeContentMax)
                .fillMaxWidth()
        } else {
            Modifier.fillMaxWidth()
        }
        // Top inset only: the dock must reach the physical screen bottom and
        // handles the nav-bar inset itself (bottom padding here would double it).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(contentWidthMod)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = settingsListHorizontalPadding())
                .padding(top = settingsListVerticalPadding())
                // Keep the last fields clear of the floating dock + dissolve.
                .padding(
                    bottom = SettingsBottomDockClearance +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isOverlay && NotificationScenes.hasLocalOverride(entry.id)) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = stringResource(R.string.notif_scene_restore),
                        color = sub.copy(alpha = 0.72f),
                        fontSize = settingsBodyTextSize(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(
                                color = if (isDark) Color.White.copy(alpha = 0.04f)
                                else Color.Black.copy(alpha = 0.035f),
                                shape = RoundedCornerShape(999.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (isDark) Color.White.copy(alpha = 0.06f)
                                else Color.Black.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(999.dp),
                            )
                            .clickable { restoreDialog.openDialog() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
            ) {
                val primary = entry.themeColors.firstOrNull() ?: "#f59e0b"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                Color(android.graphics.Color.parseColor(primary)).copy(alpha = 0.22f),
                                RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        FaIconText(
                            typeface = faTypeface,
                            icon = entry.icon,
                            color = Color(android.graphics.Color.parseColor(primary)),
                            sizeSp = 22f,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title.ifBlank { stringResource(R.string.notif_scene_title_hint) },
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val body = listOf(entry.desc, entry.subDesc).filter { it.isNotBlank() }
                            .joinToString(" ")
                        if (body.isNotBlank()) {
                            Text(
                                text = resolvePreview(body),
                                fontSize = 14.sp,
                                color = sub,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (showDupWarn) {
                Text(
                    text = stringResource(R.string.notif_scene_title_dup_warn),
                    color = Color(0xFFF59E0B),
                    fontSize = settingsBodyTextSize(),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SceneTextField(
                        label = stringResource(R.string.notif_scene_title_label),
                        hint = stringResource(R.string.notif_scene_title_hint),
                        value = entry.title,
                        labelColor = labelColor,
                        slotBg = slotBg,
                        sub = sub,
                        resolved = resolvePreview(entry.title),
                        onInsertEntity = {
                            insertTarget = "title"
                            showEntitySheet = true
                        },
                        onChange = { persist(entry.copy(title = it)) },
                    )
                    SceneTextField(
                        label = stringResource(R.string.notif_scene_desc_label),
                        hint = stringResource(R.string.notif_scene_desc_hint),
                        value = entry.desc,
                        labelColor = labelColor,
                        slotBg = slotBg,
                        sub = sub,
                        resolved = resolvePreview(entry.desc),
                        onInsertEntity = {
                            insertTarget = "desc"
                            showEntitySheet = true
                        },
                        onChange = { persist(entry.copy(desc = it)) },
                    )
                    SceneTextField(
                        label = stringResource(R.string.notif_scene_subdesc_label),
                        hint = stringResource(R.string.notif_scene_subdesc_hint),
                        value = entry.subDesc,
                        labelColor = labelColor,
                        slotBg = slotBg,
                        sub = sub,
                        resolved = resolvePreview(entry.subDesc),
                        onInsertEntity = {
                            insertTarget = "subDesc"
                            showEntitySheet = true
                        },
                        onChange = { persist(entry.copy(subDesc = it)) },
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.notif_scene_icon_label),
                        fontWeight = FontWeight.Medium,
                        fontSize = settingsTitleTextSize(),
                        color = labelColor,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BasicTextField(
                        value = iconQuery,
                        onValueChange = { iconQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = labelColor, fontSize = settingsTitleTextSize()),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(slotBg, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        decorationBox = { inner ->
                            Box {
                                if (iconQuery.isEmpty()) {
                                    Text(
                                        stringResource(R.string.notif_scene_icon_search),
                                        color = sub,
                                        fontSize = settingsTitleTextSize(),
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(48.dp),
                        state = iconGridState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        userScrollEnabled = true,
                    ) {
                        items(visibleIcons, key = { it.first }) { (name, _) ->
                            val selected = entry.icon == name
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        if (selected) accent.copy(alpha = 0.18f) else slotBg,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .then(
                                        if (selected) Modifier.border(1.5.dp, accent, RoundedCornerShape(10.dp))
                                        else Modifier
                                    )
                                    .clickable { persist(entry.copy(icon = name)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                FaIconText(
                                    typeface = faTypeface,
                                    icon = name,
                                    color = if (selected) accent else labelColor,
                                    sizeSp = 18f,
                                )
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.notif_scene_color_label),
                        fontWeight = FontWeight.Medium,
                        fontSize = settingsTitleTextSize(),
                        color = labelColor,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val primary = entry.themeColors.firstOrNull().orEmpty()
                    val isCustomColor = primary.isNotBlank() &&
                        ScenePalette.SWATCHES.none { it.hex.equals(primary, ignoreCase = true) }
                    var showColorDialog by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CustomColorSwatch(
                            selectedColor = if (isCustomColor) primary else "",
                            selected = isCustomColor,
                            enabled = true,
                            onClick = { showColorDialog = true },
                        )
                        ScenePalette.SWATCHES.forEach { sw ->
                            PresetColorSwatch(
                                color = Color(android.graphics.Color.parseColor(sw.hex)),
                                selected = primary.equals(sw.hex, ignoreCase = true),
                                enabled = true,
                                onClick = { applyColor(sw.hex) },
                            )
                        }
                    }
                    if (showColorDialog) {
                        CustomAccentColorDialog(
                            initialHex = if (isCustomColor) primary else ScenePalette.SWATCHES.first().hex,
                            onDismiss = { showColorDialog = false },
                            onConfirm = { hex ->
                                applyColor(hex)
                                showColorDialog = false
                            },
                        )
                    }
                }
            }
        }

            SettingsBottomDockCapsule(
                pageBg = screenBg,
                isDarkMode = isDark,
                primaryLabel = stringResource(R.string.notif_scene_save),
                onPrimary = { saveAndClose() },
                primaryEnabled = entry.title.isNotBlank(),
                secondaryLabel = stringResource(R.string.notif_scene_preview),
                onSecondary = { previewScene() },
                secondaryEnabled = entry.title.isNotBlank(),
                contentMaxWidth = if (isLandscape) landscapeContentMax else Dp.Unspecified,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (restoreOpen) {
        restoreDialog.ActionDialog(
            title = stringResource(R.string.notif_scene_restore),
            description = stringResource(R.string.notif_scene_restore_confirm),
            confirmLabel = stringResource(R.string.notif_scene_restore),
            confirmColor = accent,
            compact = true,
            onConfirmRequest = {
                scope.launch {
                    store.delete(entry.id)
                    NotificationScenes.clearPreviewOverride()
                    previewOverrideActive = false
                    NotificationScenes.setLocalScenes(
                        store.list().map { it.toNotificationScene() },
                    )
                    committed = true
                    dirty = false
                    withContext(Dispatchers.Main) { navController.popBackStack() }
                }
            },
        )
    }

    if (showEntitySheet && haPickerAvailable) {
        com.example.ava.homeassistant.ui.HaEntityPickerDialog(
            title = stringResource(R.string.notif_scene_insert_entity_title),
            currentValue = "",
            domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.All,
            onDismiss = { showEntitySheet = false },
            onConfirm = { id ->
                val token = "{{$id}}"
                val next = when (insertTarget) {
                    "desc" -> entry.copy(desc = entry.desc + token)
                    "subDesc" -> entry.copy(subDesc = entry.subDesc + token)
                    else -> entry.copy(title = entry.title + token)
                }
                persist(next)
                showEntitySheet = false
            },
        )
    } else if (showEntitySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val entities = remember {
            val svc = VoiceSatelliteService.getInstance()
            (svc?.getSceneEntityStates()?.keys.orEmpty() +
                svc?.getQuickEntityStates()?.keys.orEmpty())
                .distinct()
                .sorted()
        }
        val fillPane = rememberHandleSheetFill()
        ModalBottomSheet(
            onDismissRequest = { showEntitySheet = false },
            modifier = fillPane.modifier,
            sheetState = sheetState,
            sheetMaxWidth = fillPane.sheetMaxWidth,
            shape = fillPane.shape,
            containerColor = cardBg,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fillPane.fill) Modifier.fillMaxHeight() else Modifier)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.notif_scene_insert_entity_title),
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor,
                    fontSize = settingsTitleTextSize(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (entities.isEmpty()) {
                    Text(
                        stringResource(R.string.notif_scene_insert_entity_empty),
                        color = sub,
                        fontSize = settingsBodyTextSize(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    entities.take(40).forEach { eid ->
                        Text(
                            text = eid,
                            color = labelColor,
                            fontSize = settingsTitleTextSize(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val token = "{{$eid}}"
                                    val next = when (insertTarget) {
                                        "desc" -> entry.copy(desc = entry.desc + token)
                                        "subDesc" -> entry.copy(subDesc = entry.subDesc + token)
                                        else -> entry.copy(title = entry.title + token)
                                    }
                                    persist(next)
                                    showEntitySheet = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.notif_scene_insert_entity_manual), color = sub, fontSize = settingsBodyTextSize())
                var manual by remember { mutableStateOf("") }
                Spacer(modifier = Modifier.height(6.dp))
                BasicTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    singleLine = true,
                    textStyle = TextStyle(color = labelColor, fontSize = settingsTitleTextSize()),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(slotBg, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (manual.isEmpty()) {
                                Text("sensor.temperature", color = sub, fontSize = settingsTitleTextSize())
                            }
                            inner()
                        }
                    },
                )
                TextButton(
                    onClick = {
                        val id = manual.trim()
                        if (id.isNotEmpty()) {
                            val token = "{{$id}}"
                            val next = when (insertTarget) {
                                "desc" -> entry.copy(desc = entry.desc + token)
                                "subDesc" -> entry.copy(subDesc = entry.subDesc + token)
                                else -> entry.copy(title = entry.title + token)
                            }
                            persist(next)
                            showEntitySheet = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.notif_scene_insert_entity), color = accent)
                }
            }
        }
    }
}

@Composable
private fun SceneTextField(
    label: String,
    hint: String,
    value: String,
    labelColor: Color,
    slotBg: Color,
    sub: Color,
    resolved: String,
    onInsertEntity: () -> Unit,
    onChange: (String) -> Unit,
) {
    val accent = getAccentColor()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.Medium, fontSize = settingsTitleTextSize(), color = labelColor)
        TextButton(onClick = onInsertEntity, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(stringResource(R.string.notif_scene_insert_entity), color = accent, fontSize = 12.sp)
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = labelColor, fontSize = settingsTitleTextSize()),
        cursorBrush = SolidColor(accent),
        modifier = Modifier
            .fillMaxWidth()
            .background(slotBg, RoundedCornerShape(8.dp))
            .padding(12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(hint, color = sub, fontSize = settingsTitleTextSize())
                }
                inner()
            }
        },
    )
    if (value.isNotBlank() && SceneTemplateResolver.hasPlaceholders(value)) {
        Text(
            text = stringResource(R.string.notif_scene_preview_resolved, resolved),
            color = sub,
            fontSize = settingsBodyTextSize(),
        )
    }
}
