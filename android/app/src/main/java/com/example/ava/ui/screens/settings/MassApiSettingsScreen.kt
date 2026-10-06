package com.example.ava.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.massapi.MassApiClient
import com.example.ava.massapi.MassApiClientCertChooser
import com.example.ava.massapi.MassApiDiscovery
import com.example.ava.massapi.MassApiManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.AvaToast
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.rememberSettingsFlingBehavior
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.theme.SlateTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Visible discover radio cards before nested edge-fade scroll kicks in. */
private const val MassApiDiscoverVisibleCount = 2
private val MassApiDiscoverCardHeight = 56.dp
private val MassApiDiscoverCardGap = 8.dp
private val MassApiCloudServerGap = 6.dp

/**
 * massdroid-style Mass API login (frontend queue scheduling side-channel).
 * Optional Mass API side-channel. Without login, Sendspin alone owns audio /
 * seek / progress. With login, may enhance UI progress only.
 *
 * 1) Cloud status card (connection)
 * 2) Server / login card — find servers + URL / credentials
 * 3) Client certificate (mTLS) card — massdroid ClientCertCard
 *
 * Landscape: left column = connection + login · right column = mTLS (top, with cloud).
 */
@Composable
fun MassApiSettingsScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val deviceLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Split right pane is portrait-width: stacked login cards, not the two-column layout.
    val landscape = !LocalSettingsSplitActive.current && deviceLandscape
    val coroutineScope = rememberCoroutineScope()
    val manager = remember {
        MassApiManager.ensure(context).also {
            MassApiManager.bindSendspin {
                VoiceSatelliteService.getInstance()?.sendspinManager
            }
        }
    }
    val connectionState by manager.connectionState.collectAsStateWithLifecycle()
    val settings by manager.settingsFlow().collectAsStateWithLifecycle(
        initialValue = com.example.ava.settings.MassApiSettings(),
    )

    val discovery = remember { MassApiDiscovery(context) }
    val discovered by discovery.servers.collectAsStateWithLifecycle()
    val scanning by discovery.scanning.collectAsStateWithLifecycle()

    // Never auto-scan on enter — only while the user started a search, stop on leave.
    DisposableEffect(discovery) {
        onDispose { discovery.stop() }
    }
    // massdroid: reload KeyChain cert when opening this screen.
    LaunchedEffect(manager) {
        manager.loadSavedCertificate()
    }

    var editUrl by remember(settings.serverUrl) {
        mutableStateOf(TextFieldValue(settings.serverUrl, TextRange(settings.serverUrl.length)))
    }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }
    var showPassword by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var pendingP12Bytes by remember { mutableStateOf<ByteArray?>(null) }
    var p12Password by remember { mutableStateOf("") }
    var p12Error by remember { mutableStateOf<String?>(null) }
    var showP12Password by remember { mutableStateOf(false) }

    val isConnected = connectionState is MassApiClient.ConnectionState.Connected
    val isConnecting = connectionState is MassApiClient.ConnectionState.Connecting
    val hasToken = settings.authToken.isNotBlank()
    // Mass chrome only: translucent brown (dark) / muted gray (light).
    val accent = getMassChromeAccent()

    fun onSignIn() {
        val url = editUrl.text.trim().trimEnd('/')
        val user = username.trim()
        val pass = password
        if (hasToken && user.isBlank() && pass.isBlank()) {
            if (url.isBlank()) {
                loginError = context.getString(R.string.settings_mass_api_url_required)
                return
            }
            if (!url.contains("://")) {
                loginError = context.getString(R.string.settings_mass_api_url_scheme_required)
                return
            }
            loginError = null
            coroutineScope.launch { manager.connectWithToken(url) }
            return
        }
        if (url.isBlank() || user.isBlank() || pass.isBlank()) {
            loginError = context.getString(R.string.settings_mass_api_fill_all_fields)
            return
        }
        if (!url.contains("://")) {
            loginError = context.getString(R.string.settings_mass_api_url_scheme_required)
            return
        }
        loginError = null
        coroutineScope.launch { manager.login(url, user, pass) }
    }

    fun onSignOut() {
        coroutineScope.launch {
            manager.signOut()
            username = ""
            password = ""
            loginError = null
        }
    }

    fun onFindLocal() {
        if (scanning) {
            discovery.stop()
        } else {
            discovery.start()
        }
    }

    fun currentActivity(): Activity? =
        view.context.findActivity() ?: context.findActivity()

    fun restoreChooserChrome() {
        val activity = currentActivity() ?: return
        AvaSystemChrome.applyImmersiveMode(activity, deviceLandscape)
        AvaSystemChrome.installImmersiveModeListener(activity, deviceLandscape)
    }

    val p12Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        restoreChooserChrome()
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                AvaToast.show(context, R.string.settings_mass_api_client_cert_pkcs12_unreadable)
                return@launch
            }
            pendingP12Bytes = bytes
            p12Password = ""
            p12Error = null
            showP12Password = true
        }
    }

    fun openPkcs12Picker() {
        val activity = currentActivity()
        if (activity != null) {
            AvaSystemChrome.clearImmersiveModeListener(activity)
            AvaSystemChrome.showSystemBarsForDialog(activity)
        }
        p12Launcher.launch("*/*")
    }

    fun onSelectCertificate(preselect: String?) {
        val activity = currentActivity()
        if (activity == null) {
            AvaToast.show(context, R.string.settings_mass_api_client_cert_no_activity)
            return
        }
        val launched = MassApiClientCertChooser.launch(
            activity = activity,
            preselectAlias = preselect,
            serverUrl = editUrl.text.ifBlank { settings.serverUrl },
            landscape = deviceLandscape,
            onAlias = { alias -> manager.onCertificateSelected(alias, context) },
            onChooserFailed = { message ->
                AvaToast.show(
                    context,
                    context.getString(
                        R.string.settings_mass_api_client_cert_chooser_failed,
                        message,
                    ),
                )
                openPkcs12Picker()
            },
        )
        if (!launched) {
            AvaToast.show(context, R.string.settings_mass_api_client_cert_use_file)
            openPkcs12Picker()
        }
    }

    if (showP12Password) {
        AlertDialog(
            onDismissRequest = {
                showP12Password = false
                pendingP12Bytes = null
                p12Password = ""
                p12Error = null
            },
            title = {
                Text(
                    text = stringResource(R.string.settings_mass_api_client_cert_pkcs12_title),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.Bold,
                    color = getLabelColor(),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_mass_api_client_cert_pkcs12_hint),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = 0.dp,
                    )
                    OutlinedTextField(
                        value = p12Password,
                        onValueChange = {
                            p12Password = it
                            p12Error = null
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    p12Error?.let { error ->
                        Text(
                            text = error,
                            color = Color(0xFFEF4444),
                            fontSize = settingsBodyTextSize(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val bytes = pendingP12Bytes ?: return@TextButton
                        coroutineScope.launch {
                            val ok = manager.importPkcs12(bytes, p12Password)
                            if (ok) {
                                showP12Password = false
                                pendingP12Bytes = null
                                p12Password = ""
                                p12Error = null
                            } else {
                                p12Error = context.getString(
                                    R.string.settings_mass_api_client_cert_pkcs12_bad,
                                )
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.label_ok), color = accent)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showP12Password = false
                        pendingP12Bytes = null
                        p12Password = ""
                        p12Error = null
                    },
                ) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_mass_api_title),
    ) {
        if (landscape) {
            // Landscape: left = cloud + server · right = client cert, top-aligned with cloud.
            item(key = "mass_api_landscape") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MassApiCloudStatusCard(connectionState = connectionState, accent = accent)
                        Spacer(modifier = Modifier.height(12.dp))
                        MassApiServerCard(
                            isConnected = isConnected,
                            serverUrl = settings.serverUrl,
                            editUrl = editUrl,
                            onUrlChange = { editUrl = it; loginError = null },
                            username = username,
                            onUsernameChange = { username = it; loginError = null },
                            password = password,
                            onPasswordChange = { password = it; loginError = null },
                            showPassword = showPassword,
                            onTogglePassword = { showPassword = !showPassword },
                            loginError = loginError
                                ?: (connectionState as? MassApiClient.ConnectionState.Error)?.message,
                            isConnecting = isConnecting,
                            landscapeCompact = true,
                            accent = accent,
                            scanning = scanning,
                            discovered = discovered,
                            onFindLocal = ::onFindLocal,
                            onPickServer = { baseUrl ->
                                editUrl = TextFieldValue(baseUrl, TextRange(baseUrl.length))
                                loginError = null
                            },
                            onSignIn = ::onSignIn,
                            onSignOut = ::onSignOut,
                        )
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MassApiClientCertCard(
                            clientCertAlias = settings.clientCertAlias.takeIf { it.isNotBlank() },
                            accent = accent,
                            onSelectCertificate = ::onSelectCertificate,
                            onClearCertificate = { manager.clearCertificate() },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        MassApiDashedPlaceholderCard(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            item(key = "mass_api_cloud") {
                MassApiCloudStatusCard(connectionState = connectionState, accent = accent)
            }
            // Slight extra gap between cloud status and server / find-local card.
            item(key = "mass_api_cloud_gap") {
                Spacer(modifier = Modifier.height(MassApiCloudServerGap))
            }
            item(key = "mass_api_server") {
                MassApiServerCard(
                    isConnected = isConnected,
                    serverUrl = settings.serverUrl,
                    editUrl = editUrl,
                    onUrlChange = { editUrl = it; loginError = null },
                    username = username,
                    onUsernameChange = { username = it; loginError = null },
                    password = password,
                    onPasswordChange = { password = it; loginError = null },
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    loginError = loginError
                        ?: (connectionState as? MassApiClient.ConnectionState.Error)?.message,
                    isConnecting = isConnecting,
                    landscapeCompact = false,
                    accent = accent,
                    scanning = scanning,
                    discovered = discovered,
                    onFindLocal = ::onFindLocal,
                    onPickServer = { baseUrl ->
                        editUrl = TextFieldValue(baseUrl, TextRange(baseUrl.length))
                        loginError = null
                    },
                    onSignIn = ::onSignIn,
                    onSignOut = ::onSignOut,
                )
            }
            item(key = "mass_api_client_cert") {
                MassApiClientCertCard(
                    clientCertAlias = settings.clientCertAlias.takeIf { it.isNotBlank() },
                    accent = accent,
                    onSelectCertificate = ::onSelectCertificate,
                    onClearCertificate = { manager.clearCertificate() },
                )
            }
        }
    }
}

/** Empty dashed well: fills leftover height under the mTLS card in landscape. */
@Composable
private fun MassApiDashedPlaceholderCard(
    modifier: Modifier = Modifier,
) {
    val dashColor = getSliderInactiveColor()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .drawBehind {
                val strokeWidth = 1.5.dp.toPx()
                val inset = strokeWidth / 2f
                val corner = 32.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(10.dp.toPx(), 8.dp.toPx()),
                        ),
                    ),
                )
            },
    )
}

/** massdroid ClientCertCard → Ava SimpleCard (KeyChain mTLS). */
@Composable
private fun MassApiClientCertCard(
    clientCertAlias: String?,
    accent: Color,
    onSelectCertificate: (preselectAlias: String?) -> Unit,
    onClearCertificate: () -> Unit,
) {
    SimpleCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_mass_api_client_cert_title),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
            )
            if (clientCertAlias != null) {
                val selectedName = if (clientCertAlias == MassApiClientCertChooser.PKCS12_ALIAS) {
                    stringResource(R.string.settings_mass_api_client_cert_pkcs12_label)
                } else {
                    clientCertAlias
                }
                Text(
                    text = stringResource(
                        R.string.settings_mass_api_client_cert_selected,
                        selectedName,
                    ),
                    fontSize = settingsBodyTextSize(),
                    color = accent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelectCertificate(clientCertAlias) }) {
                        Text(
                            text = stringResource(R.string.settings_mass_api_client_cert_change),
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor(),
                        )
                    }
                    OutlinedButton(onClick = onClearCertificate) {
                        Text(
                            text = stringResource(R.string.settings_mass_api_client_cert_remove),
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor(),
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_mass_api_client_cert_none),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
                OutlinedButton(
                    onClick = { onSelectCertificate(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_mass_api_client_cert_select),
                        fontSize = settingsTitleTextSize(),
                        color = getLabelColor(),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** massdroid ConnectionStatusCard → Ava SimpleCard + cloud icon row. */
@Composable
private fun MassApiCloudStatusCard(
    connectionState: MassApiClient.ConnectionState,
    accent: Color,
) {
    val (icon, tint, text) = when (val s = connectionState) {
        is MassApiClient.ConnectionState.Connected -> Triple(
            Icons.Default.Cloud,
            accent,
            stringResource(R.string.settings_mass_api_status_connected, s.serverVersion),
        )
        is MassApiClient.ConnectionState.Connecting -> Triple(
            Icons.Default.CloudSync,
            accent,
            stringResource(R.string.settings_mass_api_status_connecting),
        )
        is MassApiClient.ConnectionState.Error -> Triple(
            Icons.Default.CloudOff,
            Color(0xFFEF4444),
            stringResource(R.string.settings_mass_api_status_error, s.message),
        )
        is MassApiClient.ConnectionState.Disconnected -> Triple(
            Icons.Default.CloudOff,
            getSettingsDescriptionColor(),
            stringResource(R.string.settings_mass_api_status_disconnected),
        )
    }
    SimpleCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
            )
        }
    }
}

@Composable
private fun MassApiServerCard(
    isConnected: Boolean,
    serverUrl: String,
    editUrl: TextFieldValue,
    onUrlChange: (TextFieldValue) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    loginError: String?,
    isConnecting: Boolean,
    landscapeCompact: Boolean,
    accent: Color,
    scanning: Boolean,
    discovered: List<MassApiDiscovery.MassServer>,
    onFindLocal: () -> Unit,
    onPickServer: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    // massdroid: OutlinedTextField + Column spacedBy(12); Ava accent / SimpleCard chrome kept.
    val fieldShape = RoundedCornerShape(12.dp)
    val unfocusedBorder = getSliderInactiveColor()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        unfocusedBorderColor = unfocusedBorder,
        disabledBorderColor = unfocusedBorder.copy(alpha = 0.5f),
        errorBorderColor = Color(0xFFEF4444),
        focusedLabelColor = accent,
        unfocusedLabelColor = getSettingsDescriptionColor(),
        focusedPlaceholderColor = getSettingsDescriptionColor(),
        unfocusedPlaceholderColor = getSettingsDescriptionColor(),
        errorLabelColor = Color(0xFFEF4444),
        cursorColor = accent,
        errorCursorColor = accent,
        focusedTextColor = getLabelColor(),
        unfocusedTextColor = getLabelColor(),
        errorTextColor = getLabelColor(),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        focusedLeadingIconColor = SlateTertiary,
        unfocusedLeadingIconColor = SlateTertiary,
        focusedTrailingIconColor = Color(0xFF94A3B8),
        unfocusedTrailingIconColor = Color(0xFF94A3B8),
    )
    val fieldTextStyle = TextStyle(
        fontSize = settingsTitleTextSize(),
        color = getLabelColor(),
    )
    val urlMissingScheme = editUrl.text.isNotBlank() && !editUrl.text.contains("://")
    val discoverFling = rememberSettingsFlingBehavior()
    val discoverViewportHeight =
        MassApiDiscoverCardHeight * MassApiDiscoverVisibleCount +
            MassApiDiscoverCardGap * (MassApiDiscoverVisibleCount - 1)

    SimpleCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_mass_api_server_title),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
            )

            if (isConnected) {
                // Style B: accent URL in soft well (matches discover selected card).
                Text(
                    text = serverUrl,
                    fontSize = settingsBodyTextSize(),
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(fieldShape)
                        .background(accent.copy(alpha = 0.14f))
                        .border(width = 1.dp, color = accent.copy(alpha = 0.35f), shape = fieldShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // massdroid: filled error button + left Logout icon
                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    shape = fieldShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_mass_api_sign_out),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Bold,
                    )
                }
                CollapsibleDescriptionText(
                    text = stringResource(R.string.settings_mass_api_sign_out_hint),
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    topPadding = 0.dp,
                )
            } else {
                // Find servers — merged into this card; scan only on tap (toggle to stop).
                OutlinedButton(
                    onClick = onFindLocal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = fieldShape,
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_mass_api_discover_stop),
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor(),
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_mass_api_find_local),
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor(),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                if (discovered.isNotEmpty()) {
                    val selectedUrl = editUrl.text.trim().trimEnd('/')
                    SettingsEdgeFadeScrollColumn(
                        maxHeight = discoverViewportHeight,
                        scrollEnabled = discovered.size > MassApiDiscoverVisibleCount,
                        fadeHeight = 18.dp,
                        verticalArrangement = Arrangement.spacedBy(MassApiDiscoverCardGap),
                        flingBehavior = discoverFling,
                    ) {
                        discovered.forEach { server ->
                            MassApiDiscoverRadioCard(
                                server = server,
                                selected = selectedUrl.equals(server.baseUrl, ignoreCase = true),
                                accent = accent,
                                onClick = { onPickServer(server.baseUrl) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = editUrl,
                    onValueChange = onUrlChange,
                    label = {
                        Text(
                            text = stringResource(R.string.settings_mass_api_server_url),
                            fontSize = settingsBodyTextSize(),
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.settings_mass_api_server_url_hint),
                            fontSize = settingsBodyTextSize(),
                        )
                    },
                    singleLine = true,
                    isError = urlMissingScheme,
                    supportingText = if (urlMissingScheme) {
                        {
                            Text(
                                text = stringResource(R.string.settings_mass_api_url_scheme_required),
                                fontSize = settingsBodyTextSize(),
                            )
                        }
                    } else {
                        null
                    },
                    textStyle = fieldTextStyle,
                    shape = fieldShape,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (urlMissingScheme) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(
                            onClick = {
                                val next = applyUrlScheme("http://", editUrl.text)
                                onUrlChange(TextFieldValue(next, TextRange(next.length)))
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.settings_mass_api_use_http),
                                    fontSize = settingsBodyTextSize(),
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                labelColor = getTitleColor(),
                            ),
                        )
                        SuggestionChip(
                            onClick = {
                                val next = applyUrlScheme("https://", editUrl.text)
                                onUrlChange(TextFieldValue(next, TextRange(next.length)))
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.settings_mass_api_use_https),
                                    fontSize = settingsBodyTextSize(),
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                labelColor = getTitleColor(),
                            ),
                        )
                    }
                }

                if (landscapeCompact) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = onUsernameChange,
                            label = {
                                Text(
                                    text = stringResource(R.string.settings_mass_api_username),
                                    fontSize = settingsBodyTextSize(),
                                )
                            },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            },
                            textStyle = fieldTextStyle,
                            shape = fieldShape,
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            label = {
                                Text(
                                    text = stringResource(R.string.settings_mass_api_password),
                                    fontSize = settingsBodyTextSize(),
                                )
                            },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            },
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = onTogglePassword) {
                                    Icon(
                                        if (showPassword) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = null,
                                    )
                                }
                            },
                            textStyle = fieldTextStyle,
                            shape = fieldShape,
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        label = {
                            Text(
                                text = stringResource(R.string.settings_mass_api_username),
                                fontSize = settingsBodyTextSize(),
                            )
                        },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        textStyle = fieldTextStyle,
                        shape = fieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = {
                            Text(
                                text = stringResource(R.string.settings_mass_api_password),
                                fontSize = settingsBodyTextSize(),
                            )
                        },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = onTogglePassword) {
                                Icon(
                                    if (showPassword) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        textStyle = fieldTextStyle,
                        shape = fieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                loginError?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFEF4444),
                        fontSize = settingsBodyTextSize(),
                    )
                }

                Button(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting,
                    shape = fieldShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = getMassChromeOnAccent(),
                        disabledContainerColor = accent.copy(alpha = 0.45f),
                        disabledContentColor = getMassChromeOnAccent().copy(alpha = 0.8f),
                    ),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = getMassChromeOnAccent(),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_mass_api_status_connecting),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Icon(
                            Icons.Default.Login,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_mass_api_sign_in),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_mass_api_login_help),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
            }
        }
    }
}

/**
 * Discover radio card: keep circle + border card.
 * Content is IP only (theme color) — no mDNS garbled name.
 */
@Composable
private fun MassApiDiscoverRadioCard(
    server: MassApiDiscovery.MassServer,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (selected) accent else getSliderInactiveColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MassApiDiscoverCardHeight)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) accent else getSettingsDescriptionColor(),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = massApiShortHostLabel(server),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** IP only (port when not default). No mDNS service name. */
private fun massApiShortHostLabel(server: MassApiDiscovery.MassServer): String {
    return if (server.port == MassApiDiscovery.DEFAULT_API_PORT) {
        server.host
    } else {
        "${server.host}:${server.port}"
    }
}

private fun applyUrlScheme(scheme: String, raw: String): String {
    val trimmed = raw.trim()
    val without = trimmed
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("HTTP://")
        .removePrefix("HTTPS://")
    return scheme + without
}
