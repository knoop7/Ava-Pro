package com.example.ava.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.fleet.FleetManager
import com.example.ava.fleet.FleetPasswordCodec
import com.example.ava.ui.screens.settings.components.TextSetting
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun ClusterManagementSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    // Lazy: wait for DataStore before enabling toggles (avoids flash / stuck false).
    val settingsReady = experimentalState != null
    // Serialize DataStore writes so rapid toggles don't land out of order.
    val persistMutex = remember { Mutex() }

    // Optimistic UI so switches flip immediately; store + FleetManager catch up async.
    var agentOptimistic by remember { mutableStateOf<Boolean?>(null) }
    var webOptimistic by remember { mutableStateOf<Boolean?>(null) }

    val storeAgent = experimentalState?.clusterManagementEnabled == true
    val storeWeb = experimentalState?.webConsoleEnabled == true
    val agentEnabled = agentOptimistic ?: storeAgent
    val webConsoleEnabled = webOptimistic ?: storeWeb

    val accessPasswordPlain = remember(experimentalState?.clusterAccessToken) {
        val wire = experimentalState?.clusterAccessToken.orEmpty()
        FleetPasswordCodec.decode(
            if (wire.isBlank()) FleetPasswordCodec.TOKEN_DEFAULT else FleetPasswordCodec.toWireToken(wire),
        ).ifEmpty { FleetPasswordCodec.PLAIN_DEFAULT }
    }

    // Drop optimistic overlays once store matches (or agent forced web off).
    LaunchedEffect(storeAgent, storeWeb) {
        if (agentOptimistic != null && agentOptimistic == storeAgent) agentOptimistic = null
        if (webOptimistic != null && webOptimistic == storeWeb) webOptimistic = null
        if (!storeAgent && webOptimistic == true) webOptimistic = null
    }

    var accessUrl by remember { mutableStateOf(FleetManager.getAccessUrl(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webConsoleEnabled, agentEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessUrl = FleetManager.getAccessUrl(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val switchesEnabled = settingsReady
    val passwordTooShort = stringResource(R.string.settings_cluster_access_password_too_short)
    val passwordTooLong = stringResource(R.string.settings_cluster_access_password_too_long)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_cluster_management),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_cluster_agent),
                    subLabel = stringResource(R.string.settings_cluster_agent_desc),
                ) {
                    ModernSwitch(
                        checked = agentEnabled,
                        enabled = switchesEnabled,
                        onCheckedChange = { checked ->
                            agentOptimistic = checked
                            if (!checked) webOptimistic = false
                            coroutineScope.launch(Dispatchers.IO) {
                                val ok = persistMutex.withLock {
                                    runCatching { viewModel.saveClusterManagementEnabled(checked) }
                                        .isSuccess
                                }
                                withContext(Dispatchers.Main) {
                                    if (!ok) {
                                        agentOptimistic = null
                                        if (!checked) webOptimistic = null
                                    }
                                    accessUrl = FleetManager.getAccessUrl(context)
                                }
                            }
                        },
                    )
                }

                // Website console only when agent is on (lazy section).
                if (agentEnabled) {
                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_web_console),
                        subLabel = stringResource(R.string.settings_web_console_desc),
                    ) {
                        ModernSwitch(
                            checked = webConsoleEnabled,
                            enabled = switchesEnabled,
                            onCheckedChange = { checked ->
                                webOptimistic = checked
                                coroutineScope.launch(Dispatchers.IO) {
                                    val ok = persistMutex.withLock {
                                        runCatching { viewModel.saveWebConsoleEnabled(checked) }
                                            .isSuccess
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (!ok) webOptimistic = null
                                        accessUrl = FleetManager.getAccessUrl(context)
                                    }
                                }
                            },
                        )
                    }

                    SettingsDivider()

                    TextSetting(
                        name = stringResource(R.string.settings_cluster_access_password),
                        description = stringResource(R.string.settings_cluster_access_password_hint),
                        dialogHint = stringResource(R.string.settings_cluster_access_password_dialog),
                        value = accessPasswordPlain,
                        rowValue = stringResource(R.string.settings_cluster_access_password_set),
                        enabled = switchesEnabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        validation = { input ->
                            val t = input.trim()
                            when {
                                t.length < 4 -> passwordTooShort
                                t.toByteArray(Charsets.UTF_8).size > 12 -> passwordTooLong
                                else -> null
                            }
                        },
                        onConfirmRequest = { next ->
                            coroutineScope.launch(Dispatchers.IO) {
                                persistMutex.withLock {
                                    runCatching { viewModel.saveClusterAccessPassword(next.trim()) }
                                }
                                withContext(Dispatchers.Main) {
                                    accessUrl = FleetManager.getAccessUrl(context)
                                }
                            }
                        },
                    )

                    if (webConsoleEnabled) {
                        SettingsDivider()
                        ClusterAccessUrlRow(
                            accessUrl = accessUrl,
                            onOpenUrl = {
                                val openUrl = FleetManager.getAccessUrl(context, includeToken = true)
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterAccessUrlRow(
    accessUrl: String,
    onOpenUrl: () -> Unit,
) {
    val noNetwork = accessUrl.contains("127.0.0.1")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !noNetwork, onClick = onOpenUrl)
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_cluster_management_access_url),
            color = getTitleColor(),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (noNetwork) {
                stringResource(R.string.settings_cluster_management_no_network)
            } else {
                accessUrl
            },
            color = if (noNetwork) getSettingsDescriptionColor() else getAccentColor(),
            fontSize = settingsBodyTextSize(),
            lineHeight = 18.sp,
        )
        if (!noNetwork) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_cluster_management_access_url_hint),
                color = getSettingsDescriptionColor(),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
            )
        }
    }
}
