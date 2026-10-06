package com.example.ava.ui.screens.settings

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.notifications.FontAwesomeHelper
import com.example.ava.notifications.NotificationScene
import com.example.ava.notifications.NotificationScenes
import com.example.ava.services.NotificationOverlayService
import com.example.ava.settings.localScenesStore
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.components.ActionDialog
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.DialogScope
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared scene list for the notification entry card (below the two style nav rows).
 */
@Composable
fun NotificationScenesSharedBlock(
    navController: NavController,
    enabled: Boolean,
    coroutineScope: CoroutineScope,
    /** When false, the header omits New — use a floating dock on the library page instead. */
    showNewSceneAction: Boolean = true,
) {
    val context = LocalContext.current
    val accent = getAccentColor()
    val label = getLabelColor()
    val refresh = NotificationScenes.refreshCount.value
    val scenes = remember(refresh) { NotificationScenes.ALL_SCENES }
    val fa = remember { FontAwesomeHelper.loadFont(context) }
    val store = remember { context.localScenesStore }
    var pendingDelete by remember { mutableStateOf<NotificationScene?>(null) }
    var pendingRestore by remember { mutableStateOf<NotificationScene?>(null) }
    val deleteDialog = remember { DialogScope() }
    val restoreDialog = remember { DialogScope() }
    val deleteOpen by deleteDialog.isDialogOpen.collectAsStateWithLifecycle()
    val restoreOpen by restoreDialog.isDialogOpen.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.notif_scenes_list_title),
                color = label,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.notif_scenes_list_desc),
                color = getSettingsDescriptionColor(),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                topPadding = 0.dp,
            )
        }
        if (showNewSceneAction) {
            TextButton(
                onClick = {
                    navController.navigate("${Screen.SETTINGS_INTERACTION_SCENE_EDIT}/new") {
                        launchSingleTop = true
                    }
                },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.notif_scene_new), color = accent)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    // User section: pure local_* + saved overlays; then divider; then untouched catalog.
    val userScenes = remember(scenes) {
        scenes.filter { NotificationScenes.isUserPinnedScene(it.id) }
    }
    val otherScenes = remember(scenes) {
        scenes.filter { !NotificationScenes.isUserPinnedScene(it.id) }
    }

    @Composable
    fun SceneRow(scene: NotificationScene) {
        val isPureLocal = NotificationScenes.isLocalScene(scene.id)
        val isUrl = NotificationScenes.isCustomUrlScene(scene.id)
        val overridden = !isPureLocal && NotificationScenes.hasLocalOverride(scene.id)
        val source = when {
            isPureLocal -> stringResource(R.string.notif_scene_source_local)
            isUrl && overridden -> stringResource(R.string.notif_scene_source_url_edited)
            isUrl -> stringResource(R.string.notif_scene_source_url)
            overridden -> stringResource(R.string.notif_scene_source_builtin_edited)
            else -> stringResource(R.string.notif_scene_source_builtin)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable(enabled = enabled) {
                    navController.navigate(
                        "${Screen.SETTINGS_INTERACTION_SCENE_EDIT}/${scene.id}"
                    ) { launchSingleTop = true }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaIconText(
                typeface = fa,
                icon = scene.icon,
                color = Color(scene.getPrimaryColor()),
                sizeSp = 20f,
                modifier = Modifier.width(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scene.title,
                    color = label,
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(scene.desc, scene.subDesc).filter { it.isNotBlank() }
                        .joinToString(" ").ifBlank { source },
                    color = getSettingsDescriptionColor(),
                    fontSize = settingsBodyTextSize(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { NotificationOverlayService.previewScene(context, scene.id) },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.notif_scene_preview), color = accent, fontSize = 12.sp)
            }
            when {
                isPureLocal -> TextButton(
                    onClick = {
                        pendingDelete = scene
                        deleteDialog.openDialog()
                    },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.notif_scene_delete), color = Color(0xFFEF4444), fontSize = 12.sp)
                }
                overridden -> TextButton(
                    onClick = {
                        pendingRestore = scene
                        restoreDialog.openDialog()
                    },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.notif_scene_restore), color = getSettingsDescriptionColor(), fontSize = 12.sp)
                }
            }
        }
    }

    userScenes.forEach { SceneRow(it) }
    if (userScenes.isNotEmpty() && otherScenes.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(4.dp))
    }
    otherScenes.forEach { SceneRow(it) }

    if (deleteOpen) {
        val scene = pendingDelete
        deleteDialog.ActionDialog(
            title = stringResource(R.string.notif_scene_delete),
            description = stringResource(R.string.notif_scene_delete_confirm),
            confirmLabel = stringResource(R.string.notif_scene_delete),
            confirmColor = Color(0xFFEF4444),
            compact = true,
            onDismissRequest = { pendingDelete = null },
            onConfirmRequest = {
                val id = scene?.id ?: return@ActionDialog
                pendingDelete = null
                coroutineScope.launch {
                    store.delete(id)
                    NotificationScenes.setLocalScenes(
                        store.list().map { it.toNotificationScene() },
                    )
                }
            },
        )
    }

    if (restoreOpen) {
        val scene = pendingRestore
        restoreDialog.ActionDialog(
            title = stringResource(R.string.notif_scene_restore),
            description = stringResource(R.string.notif_scene_restore_confirm),
            confirmLabel = stringResource(R.string.notif_scene_restore),
            confirmColor = accent,
            compact = true,
            onDismissRequest = { pendingRestore = null },
            onConfirmRequest = {
                val id = scene?.id ?: return@ActionDialog
                pendingRestore = null
                coroutineScope.launch {
                    store.delete(id)
                    NotificationScenes.setLocalScenes(
                        store.list().map { it.toNotificationScene() },
                    )
                }
            },
        )
    }
}

@Composable
internal fun FaIconText(
    typeface: Typeface,
    icon: String,
    color: Color,
    sizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val glyph = FontAwesomeHelper.getIconChar(icon)
    val argb = color.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                this.typeface = typeface
                textSize = sizeSp
                setTextColor(argb)
                text = glyph
            }
        },
        update = { tv ->
            tv.typeface = typeface
            tv.textSize = sizeSp
            tv.setTextColor(argb)
            tv.text = glyph
        },
    )
}
