package com.example.ava.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ava.R
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.audio.eq.MusicEqGains
import com.example.ava.audio.eq.MusicEqPreset
import com.example.ava.audio.eq.MusicEqRuntime
import com.example.ava.audio.eq.MusicEqSource
import com.example.ava.audio.eq.toMusicEqGains
import com.example.ava.lyrics.LyricDisplayRuntime
import com.example.ava.massapi.MassApiManager
import com.example.ava.massapi.MassPlayer
import com.example.ava.massapi.MassPlaylist
import com.example.ava.massapi.MassPlaylistTrack
import com.example.ava.massapi.MassQueueItem
import com.example.ava.massapi.MassSearchItem
import com.example.ava.services.VinylCoverService
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.voice.AvaSyncOffsetPeer
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.settings.SendspinSettingsStore
import com.example.ava.settings.sendspinSettingsStore
import com.example.ava.ui.CautiousMarqueeText
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getMassChromeAccent
import com.example.ava.ui.screens.settings.getMassChromeOnAccent
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.stripParenthetical
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Positional Icon args — avoids Lite mis-resolve of named `modifier=` on Icon. */
@Composable
private fun RailIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    size: Dp,
    tint: Color,
) {
    Icon(imageVector, contentDescription, Modifier.size(size), tint)
}

/** Speaker cabinet glyph (matches design-previews SPEAKER_SVG). */
private val RailSpeakerIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailSpeaker",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 2f)
            horizontalLineTo(7f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(16f)
            curveToRelative(0f, 1.1f, 0.9f, 1.99f, 2f, 1.99f)
            lineTo(17f, 22f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(4f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(12f, 4f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
            reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
            reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
            close()
            moveTo(12f, 12f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            close()
        }
    }.build()
}

/** Left↔right swap arrows (Material SwapHoriz). */
private val RailSwapHorizIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailSwapHoriz",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.99f, 11f)
            lineTo(3f, 15f)
            lineToRelative(3.99f, 4f)
            verticalLineToRelative(-3f)
            horizontalLineTo(14f)
            verticalLineToRelative(-2f)
            horizontalLineTo(6.99f)
            verticalLineToRelative(-3f)
            close()
            moveTo(21f, 9f)
            lineToRelative(-3.99f, -4f)
            verticalLineToRelative(3f)
            horizontalLineTo(10f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(7.01f)
            verticalLineToRelative(3f)
            lineTo(21f, 9f)
            close()
        }
    }.build()
}

/** Sleep moon (matches design-previews MOON_SVG). */
private val RailMoonIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailMoon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.34f, 2.02f)
            curveTo(6.59f, 1.82f, 2f, 6.42f, 2f, 12f)
            curveToRelative(0f, 5.52f, 4.48f, 10f, 10f, 10f)
            curveToRelative(3.71f, 0f, 6.93f, -2.02f, 8.66f, -5.02f)
            curveToRelative(-7.51f, -0.25f, -12.09f, -8.43f, -8.32f, -14.96f)
            close()
        }
    }.build()
}

/** Sync-delay hub glyph (MDI sync arrows). */
private val RailSyncDelayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailSyncDelay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M12,18A6,6 0 0,1 6,12C6,11 6.25,10.03 6.7,9.2L5.24,7.74" +
                    "C4.46,8.97 4,10.43 4,12A8,8 0 0,0 12,20V23L16,19L12,15" +
                    "M12,4V1L8,5L12,9V6A6,6 0 0,1 18,12C18,13 17.75,13.97 17.3,14.8" +
                    "L18.76,16.26C19.54,15.03 20,13.57 20,12A8,8 0 0,0 12,4Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** Equalizer hub glyph (Material Equalizer bars). */
private val RailEqIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailEq",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M10,20H14V4H10V20M4,20H8V12H4V20M16,20H20V8H16V20Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** Lyric tuner hub glyph (two text lines). */
private val RailLyricsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailLyrics",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M4,9H20V11H4V9M4,13H16V15H4V13Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** Filled heart — favorited state (same ink as the rail, not a special red). */
private val RailHeartFilledIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailHeartFilled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 21.35f)
            lineToRelative(-1.45f, -1.32f)
            curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
            curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
            curveToRelative(1.74f, 0f, 3.41f, 0.81f, 4.5f, 2.09f)
            curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
            curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
            curveToRelative(0f, 3.78f, -3.4f, 6.86f, -8.55f, 11.54f)
            lineTo(12f, 21.35f)
            close()
        }
    }.build()
}

/** Outline heart — not favorited (Material FavoriteBorder). */
private val RailHeartOutlineIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RailHeartOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.5f, 3f)
            curveToRelative(-1.74f, 0f, -3.41f, 0.81f, -4.5f, 2.09f)
            curveTo(10.91f, 3.81f, 9.24f, 3f, 7.5f, 3f)
            curveTo(4.42f, 3f, 2f, 5.42f, 2f, 8.5f)
            curveToRelative(0f, 3.78f, 3.4f, 6.86f, 8.55f, 11.54f)
            lineTo(12f, 21.35f)
            lineToRelative(1.45f, -1.32f)
            curveTo(18.6f, 15.36f, 22f, 12.28f, 22f, 8.5f)
            curveTo(22f, 5.42f, 19.58f, 3f, 16.5f, 3f)
            close()
            moveTo(12.1f, 18.55f)
            lineToRelative(-0.1f, 0.1f)
            lineToRelative(-0.1f, -0.1f)
            curveTo(7.14f, 14.24f, 4f, 11.39f, 4f, 8.5f)
            curveTo(4f, 6.5f, 5.5f, 5f, 7.5f, 5f)
            curveToRelative(1.54f, 0f, 3.04f, 0.99f, 3.57f, 2.36f)
            horizontalLineToRelative(1.87f)
            curveTo(13.46f, 5.99f, 14.96f, 5f, 16.5f, 5f)
            curveToRelative(2f, 0f, 3.5f, 1.5f, 3.5f, 3.5f)
            curveToRelative(0f, 2.89f, -3.14f, 5.74f, -7.9f, 10.05f)
            close()
        }
    }.build()
}

/** Peer is paired with our local player (leader lists members; peer may set synced_to). */
private fun isPairedWithSelf(peer: MassPlayer, selfId: String, self: MassPlayer?): Boolean {
    if (peer.playerId == selfId || selfId.isBlank()) return false
    if (peer.syncedTo == selfId) return true
    if (peer.playerId in self?.groupChilds.orEmpty()) return true
    // synced_to / group_childs can lag right after saving members — a peer whose
    // active source points at us and plays our track is in the group (same
    // signal transfer mode trusts); without this the chip undercounts (+2 for 3).
    return looksSyncedBySameTrack(peer, self)
}

/**
 * Either end of an active MA sync pair — no "I must be the leader" gate.
 *
 * MA writes `group_members` on the leader and `synced_to` on followers, so
 * [isPairedWithSelf] is one-way. Transfer / audio-page draft / "+N" keep that
 * leader view; delay and EQ only need "we are already synced" from either seat.
 *
 * UDP stream-key proximity is not pairing — unpaired devices on the same
 * Music Assistant used to light the mark and follow pause/seek.
 */
private fun isInSameSyncGroup(
    peer: MassPlayer,
    selfId: String,
    self: MassPlayer?,
    @Suppress("UNUSED_PARAMETER") syncPlayingPeers: Set<String> = emptySet(),
): Boolean {
    if (peer.playerId == selfId) return false
    if (selfId.isBlank()) return false
    if (isPairedWithSelf(peer, selfId, self)) return true
    // Only a real player id. JSON null parsed as "null" would make every
    // unsynced MA player look like a sibling of every other unsynced player.
    val leaderId = realMassPlayerId(self?.syncedTo) ?: return selfId in peer.groupChilds
    if (peer.playerId == leaderId || realMassPlayerId(peer.syncedTo) == leaderId) {
        return true
    }
    return selfId in peer.groupChilds
}

/**
 * Ava peers currently rendering our Sendspin stream, sampled on the beacon
 * cadence. MA emits nothing when only the audio grouping moves, so waiting for
 * a [MassPlayer] update would leave this stale — poll instead.
 */
@Composable
private fun rememberSyncPlayingPeers(): Set<String> {
    var peers by remember { mutableStateOf(AvaSyncOffsetPeer.peersSyncPlayingWithUs()) }
    LaunchedEffect(Unit) {
        while (true) {
            peers = AvaSyncOffsetPeer.peersSyncPlayingWithUs()
            kotlinx.coroutines.delay(1_500)
        }
    }
    return peers
}

/** Reject blank / JSON-null leftovers so they cannot form a sync group. */
private fun realMassPlayerId(id: String?): String? {
    val v = id?.trim().orEmpty()
    if (v.isEmpty() ||
        v.equals("null", ignoreCase = true) ||
        v.equals("undefined", ignoreCase = true)
    ) {
        return null
    }
    return v
}

/**
 * Same non-blank track as [self] — used in transfer mode when MA group fields lag,
 * so a peer already playing our song is treated as synced (transfer disabled).
 *
 * Requires an active sync signal (`synced_to` / `active_source` → self). Equal
 * titles alone after unpair must not keep blocking transfer / sync UI.
 */
private fun looksSyncedBySameTrack(
    peer: MassPlayer,
    self: MassPlayer?,
    fallbackSelfTrack: String = "",
): Boolean {
    if (self == null) return false
    val selfId = self.playerId
    val stillPointingAtSelf =
        peer.syncedTo == selfId || peer.activeSource == selfId
    if (!stillPointingAtSelf) return false
    val a = stripParenthetical(peer.trackTitle).ifBlank { peer.trackTitle }.trim()
    val selfRaw = self.trackTitle.ifBlank { fallbackSelfTrack }
    val b = stripParenthetical(selfRaw).ifBlank { selfRaw }.trim()
    if (a.isBlank() || b.isBlank()) return false
    if (!a.equals(b, ignoreCase = true)) return false
    // Idle/empty peers with a stale title shouldn't lock transfer.
    val peerBusy = peer.state.equals("playing", ignoreCase = true) ||
        peer.state.equals("paused", ignoreCase = true)
    val selfBusy = self.state.equals("playing", ignoreCase = true) ||
        self.state.equals("paused", ignoreCase = true)
    return peerBusy && selfBusy
}

/**
 * Transfer target is already in our sync group (or looks like it via same track).
 * Uses the bidirectional view: from a follower seat the leader-only test misses
 * every group member, which would offer transfer to a device already playing
 * with us.
 */
private fun isTransferBlockedPeer(
    peer: MassPlayer,
    selfId: String,
    self: MassPlayer?,
    fallbackSelfTrack: String = "",
    syncPlayingPeers: Set<String> = emptySet(),
): Boolean {
    if (peer.playerId == selfId) return false
    return isInSameSyncGroup(peer, selfId, self, syncPlayingPeers) ||
        looksSyncedBySameTrack(peer, self, fallbackSelfTrack)
}

/** Sync-delay / EQ peer probes: retries so a dropped beacon is not a permanent hint row. */
private const val PEER_PROBE_ATTEMPTS = 3

/**
 * Gap before re-probing rows that are still unresolved. Peers beacon every 5s
 * and presence may come up after the page did, so a first-pass miss must not
 * latch "not supported" for good.
 */
private const val PEER_PROBE_RETRY_MS = 6_000L

private val RailInk = Color(0xFFF4EFE8)
private val RailMuted = Color(0x94F4EFE8)
private val RailFaint = Color(0x4DF4EFE8)
private val RailLine = Color(0x1FF4EFE8)
private val RailWell = Color(0xFF0A0908)
/** Portrait translucent well over NP — dark theme (unchanged). */
private const val RailTranslucentAlphaDark = 0.82f
/** Portrait translucent well — light theme, slightly deeper so NP does not wash through. */
private const val RailTranslucentAlphaLight = 0.90f
/** Dark ink on light/soft-brown fills (toast / cream surfaces). */
private val RailOnLight = Color(0xFF1A140C)

/** How many playlist tracks to paint per bottom-reach step. */
private const val PLAYLIST_UI_CHUNK = 30
/** Pause before each MA tracks page so fling-scroll cannot hammer the server. */
private const val PLAYLIST_PAGE_GAP_MS = 280L
/**
 * Open-scroll tail for Up Next. Farther than this, [scrollToItem] lands just above
 * the window and only the last viewport is animated — so a current track at row 40
 * does not compose every row on the way down. About one rail viewport of rows.
 */
private const val QUEUE_OPEN_SCROLL_TAIL_ROWS = 8
/** Local More toggle: search taps add one song instead of replacing the queue. */
private const val KEY_RAIL_SEARCH_ADD_TO_QUEUE = "mass_rail_search_add_to_queue"
/** Local More toggle: FAB expand/collapse also opens or closes the paired now-playing page. */
private const val KEY_RAIL_SYNC_FAB_EXPAND = VinylCoverService.PREF_SYNC_PEER_EXPAND
/**
 * Grace for the host window to come back from `updateViewLayout` as focusable before the
 * search field claims editor focus — see [RailSearchBar]. One frame is usually enough; this
 * leaves room for a loaded first frame without being long enough to feel like lag.
 */
private const val IME_WINDOW_SETTLE_MS = 90L
/**
 * How long the mic button waits for the Assist pipeline to transcribe something. Generous
 * enough to cover speaking plus HA's STT round trip, but bounded so a pipeline that never
 * reports back cannot leave the button stuck listening.
 */
private const val VOICE_SEARCH_TIMEOUT_MS = 15_000L

/**
 * Theme chrome for the rail via [getMassChromeAccent]:
 * dark → translucent brown; light → muted gray (Mass pages only).
 */
private data class MassRailPalette(
    val accent: Color,
    val onAccent: Color,
) {
    val accentSoft: Color get() = accent
}

private val LocalMassRailPalette = compositionLocalOf {
    MassRailPalette(
        accent = AccentChromeFallbackBrown,
        onAccent = RailOnLight,
    )
}

/** CompositionLocal default before a provider is set (dark soft brown). */
private val AccentChromeFallbackBrown = Color(0xE0A78B73)

@Composable
private fun rememberMassRailPalette(): MassRailPalette {
    val accent = getMassChromeAccent()
    val onAccent = getMassChromeOnAccent()
    return remember(accent, onAccent) {
        MassRailPalette(accent = accent, onAccent = onAccent)
    }
}


/**
 * Screen-scaled Mass rail chrome — same ladder as NP [rememberDetailOverlayMetrics]:
 * 320dp ≈ 0.9 · 360dp phone = 1.0 · tablets up to ~1.55.
 * Base numbers below are the previous phone hardcodes.
 */
private data class MassRailMetrics(
    val scale: Float,
    val padH: Dp,
    val padV: Dp,
    val gapSm: Dp,
    val gapMd: Dp,
    val captionSp: TextUnit,
    val secondarySp: TextUnit,
    val bodySp: TextUnit,
    val titleSp: TextUnit,
    val hubTitleSp: TextUnit,
    val subBarTitleSp: TextUnit,
    val chevronSp: TextUnit,
    val headCloseSize: Dp,
    val headCloseIcon: Dp,
    val headChipIcon: Dp,
    val headChipTextSp: TextUnit,
    val headChipPadV: Dp,
    val headChipPadH: Dp,
    val headRowPadV: Dp,
    val tabPadV: Dp,
    val rowPadV: Dp,
    val rowPadH: Dp,
    val iconSm: Dp,
    val iconMd: Dp,
    val iconLg: Dp,
    val tileSize: Dp,
    val avatarSize: Dp,
    val checkOuter: Dp,
    val checkInner: Dp,
    val checkIcon: Dp,
    val backHit: Dp,
    val backIcon: Dp,
    val hubIconBox: Dp,
    val hubIcon: Dp,
    val chipIcon: Dp,
    val chipPadH: Dp,
    val chipPadV: Dp,
    val footPadH: Dp,
    val footPadV: Dp,
    val cornerSm: Dp,
    val cornerMd: Dp,
    val cornerLg: Dp,
    val indexWidth: Dp,
    val pushHitW: Dp,
    val pushHitH: Dp,
    val pushVisualW: Dp,
    val pushVisualH: Dp,
    val pushIcon: Dp,
    val pushCorner: Dp,
)

private val LocalMassRailMetrics = compositionLocalOf<MassRailMetrics> {
    error("MassRailMetrics not provided")
}

@Composable
private fun rememberMassRailMetrics(): MassRailMetrics {
    val configuration = LocalConfiguration.current
    val vmin = min(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    return remember(vmin, isPortrait) {
        // Match NP overlay: phone 360dp → 1.0
        val scale = (vmin / 360f).coerceIn(0.9f, 1.55f)
        // Head close + device chip: modest default bump, then screen scale.
        // Portrait full-bleed gets a touch more; landscape stays restrained.
        val headBoost = if (isPortrait) 1.10f else 1.06f
        fun d(base: Float, lo: Float = base * 0.9f, hi: Float = base * 1.55f): Dp =
            (base * scale).coerceIn(lo, hi).dp
        fun t(base: Float, lo: Float = base * 0.9f, hi: Float = base * 1.55f): TextUnit =
            (base * scale).coerceIn(lo, hi).sp
        fun hd(base: Float, lo: Float = base * 0.9f, hi: Float = base * 1.65f): Dp =
            (base * scale * headBoost).coerceIn(lo, hi).dp
        fun ht(base: Float, lo: Float = base * 0.9f, hi: Float = base * 1.65f): TextUnit =
            (base * scale * headBoost).coerceIn(lo, hi).sp
        MassRailMetrics(
            scale = scale,
            // +5dp vs the old 12dp base — right rail felt tight on both edges.
            padH = d(17f),
            padV = d(10f),
            gapSm = d(6f, 4f, 10f),
            gapMd = d(10f, 8f, 16f),
            captionSp = t(12f, 11f, 17f),
            secondarySp = t(13f, 12f, 18f),
            bodySp = t(14f, 13f, 20f),
            titleSp = t(15f, 14f, 22f),
            hubTitleSp = t(16f, 14f, 24f),
            subBarTitleSp = t(17f, 15f, 26f),
            chevronSp = t(28f, 24f, 40f),
            // Defaults slightly above the old 36/20/16/13 hardcodes.
            headCloseSize = hd(40f, 36f, 56f),
            headCloseIcon = hd(22f, 19f, 30f),
            headChipIcon = hd(18f, 16f, 25f),
            headChipTextSp = ht(14f, 13f, 19f),
            headChipPadV = hd(11f, 9f, 15f),
            headChipPadH = hd(14f, 12f, 19f),
            headRowPadV = hd(11f, 9f, 16f),
            tabPadV = d(10f, 8f, 14f),
            rowPadV = d(12f, 10f, 18f),
            rowPadH = d(10f, 8f, 14f),
            iconSm = d(14f, 12f, 20f),
            iconMd = d(20f, 17f, 28f),
            iconLg = d(22f, 18f, 32f),
            tileSize = d(44f, 38f, 64f),
            avatarSize = d(40f, 34f, 56f),
            checkOuter = d(40f, 34f, 56f),
            checkInner = d(22f, 18f, 30f),
            checkIcon = d(14f, 12f, 20f),
            backHit = d(36f, 32f, 50f),
            backIcon = d(28f, 24f, 40f),
            hubIconBox = d(44f, 38f, 64f),
            hubIcon = d(22f, 18f, 32f),
            chipIcon = d(16f, 14f, 22f),
            chipPadH = d(10f, 8f, 14f),
            chipPadV = d(7f, 6f, 11f),
            footPadH = d(16f, 14f, 24f),
            footPadV = d(11f, 9f, 16f),
            cornerSm = d(6f, 5f, 10f),
            cornerMd = d(12f, 10f, 18f),
            cornerLg = d(14f, 12f, 22f),
            indexWidth = d(28f, 24f, 40f),
            pushHitW = d(48f, 42f, 68f),
            pushHitH = d(96f, 84f, 136f),
            pushVisualW = d(28f, 24f, 40f),
            pushVisualH = d(84f, 72f, 120f),
            pushIcon = d(18f, 16f, 26f),
            pushCorner = d(14f, 12f, 20f),
        )
    }
}


private enum class RailTab { UpNext, Library, Sync }
private enum class RailSub { None, Audio, Speakers, SyncDelay, Equalizer, Lyrics, Sleep, More }

/**
 * Mass overlay well — matches design-previews/mass-api-overlay-rail.html IA:
 * tabs Up Next / Library / Sync · Audio subpage (sync + switch) · Speakers · Sleep · More.
 */
@Composable
fun MassApiOverlayRail(
    onClose: () -> Unit,
    railModifier: Modifier = Modifier,
    /** Portrait: full-bleed well with a slightly see-through panel over NP. */
    translucent: Boolean = false,
    /**
     * Cold-start waiting shell: the upstream queue may still carry a stale
     * "current" pick. UI-only — hide the selection so Up Next never
     * contradicts the waiting player; with no current row, every row stays
     * tappable and plays via play_index once the compact intro is opened.
     */
    waitingForMedia: Boolean = false,
) {
    // Collect the live instance: remember { MassApiManager.get() } would cache
    // null forever if the rail composed before the service ran ensure().
    val manager by MassApiManager.instanceFlow.collectAsState()
    val context = LocalContext.current
    // Collect unconditionally so the number of collectAsState slots never changes
    // when `manager` flips null→non-null; that shift dropped connection updates and
    // left the lists stuck empty. emptyFlow() is a stable singleton that never emits.
    val connected by (manager?.connectionState ?: emptyFlow()).collectAsState(initial = null)
    val players by (manager?.players ?: emptyFlow())
        .collectAsState(initial = emptyList<MassPlayer>())
    val rawQueueItems by (manager?.queueItems ?: emptyFlow())
        .collectAsState(initial = emptyList<MassQueueItem>())
    val queueItems = if (waitingForMedia) {
        rawQueueItems.map { if (it.isCurrent) it.copy(isCurrent = false) else it }
    } else {
        rawQueueItems
    }
    val playlists by (manager?.playlists ?: emptyFlow())
        .collectAsState(initial = emptyList<MassPlaylist>())
    val searchResults by (manager?.searchResults ?: emptyFlow())
        .collectAsState(initial = emptyList<MassSearchItem>())
    val searchBusy by (manager?.searchBusy ?: emptyFlow()).collectAsState(initial = false)
    val activePlayerId by (manager?.activePlayerId ?: emptyFlow()).collectAsState(initial = "")
    val sleepMinutes by (manager?.sleepMinutes ?: emptyFlow()).collectAsState(initial = 0)
    val autoplayEnabled by (manager?.autoplayEnabled ?: emptyFlow()).collectAsState(initial = false)
    val crossfadeEnabled by (manager?.crossfadeEnabled ?: emptyFlow()).collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val audioLabel = stringResource(R.string.mass_api_rail_audio)
    val soloLabel = stringResource(R.string.mass_api_rail_solo)
    val failedLabel = stringResource(R.string.mass_api_rail_failed)
    val offLabel = stringResource(R.string.mass_api_rail_off)

    var tab by remember { mutableStateOf(RailTab.UpNext) }
    var sub by remember { mutableStateOf(RailSub.None) }
    /** Library detail — tap opens; never auto-plays. Cleared on back / leaving rail. */
    var openPlaylist by remember { mutableStateOf<MassPlaylist?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    /** Single-flight for search-result taps, mirroring the playlist detail page. */
    var searchPlayBusy by remember { mutableStateOf(false) }
    var searchPlayKey by remember { mutableStateOf<String?>(null) }

    val metrics = rememberMassRailMetrics()
    val palette = rememberMassRailPalette()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val searchAddToQueue by rememberBooleanPreference(prefs, KEY_RAIL_SEARCH_ADD_TO_QUEUE, true)
    val syncFabExpand by rememberBooleanPreference(prefs, KEY_RAIL_SYNC_FAB_EXPAND, false)
    // Hub EQ entry only exists while the local 5-band music EQ is switched on.
    val railEqStore = remember(context) { SendspinSettingsStore(context.sendspinSettingsStore) }
    val railEqEnabled by railEqStore.musicEqEnabled.collectAsState(initial = false)
    // Portrait-only (`translucent`): light theme well a bit deeper so cover/NP
    // does not wash the panel; dark keeps the original see-through.
    val wellColor = when {
        !translucent -> RailWell
        isDarkMode -> RailWell.copy(alpha = RailTranslucentAlphaDark)
        else -> RailWell.copy(alpha = RailTranslucentAlphaLight)
    }

    LaunchedEffect(manager, connected) {
        manager?.takeIf { it.isConnected() }?.refreshRail()
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(1500)
            toast = null
        }
    }
    // Safety: clear a hung play wait if the RPC never returns.
    LaunchedEffect(searchPlayBusy, searchPlayKey) {
        if (!searchPlayBusy) return@LaunchedEffect
        val key = searchPlayKey
        delay(8_000)
        if (searchPlayBusy && searchPlayKey == key) {
            searchPlayBusy = false
            searchPlayKey = null
        }
    }
    DisposableEffect(manager) {
        // Stale hits must not greet the next open of the rail.
        onDispose { manager?.clearSearch() }
    }
    LaunchedEffect(searchAddToQueue) {
        if (searchQuery.isNotBlank()) {
            manager?.searchAsync(searchQuery, tracksOnly = searchAddToQueue)
        }
    }

    val activeName = players.firstOrNull { it.playerId == activePlayerId }?.displayName
        ?: activePlayerId
    val selfPlayer = players.firstOrNull { it.playerId == activePlayerId }
    val railSyncPlaying = rememberSyncPlayingPeers()
    val syncCount = players.count {
        isInSameSyncGroup(it, activePlayerId, selfPlayer, railSyncPlaying)
    }
    val queueTrack = queueItems.firstOrNull { it.isCurrent }?.title.orEmpty()

    CompositionLocalProvider(
        LocalMassRailMetrics provides metrics,
        LocalMassRailPalette provides palette,
    ) {
    Column(
        modifier = railModifier
            .fillMaxHeight()
            .background(wellColor)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.05f)),
    ) {
        // Head: close + route chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.padH, vertical = metrics.headRowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(metrics.headCloseSize)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                RailIcon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.mass_api_rail_close),
                    size = metrics.headCloseIcon,
                    tint = RailInk.copy(alpha = 0.72f),
                )
            }
            Spacer(Modifier.width(metrics.gapMd))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable {
                        openPlaylist = null
                        sub = RailSub.Audio
                        manager?.refreshRail()
                    }
                    .padding(horizontal = metrics.headChipPadH, vertical = metrics.headChipPadV),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RailIcon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = null,
                    size = metrics.headChipIcon,
                    tint = LocalMassRailPalette.current.accent,
                )
                Spacer(Modifier.width(metrics.gapMd))
                Text(
                    // "+N" = additional synced devices — smaller, muted, raised to
                    // sit optically centered so it reads as a quiet badge.
                    text = buildAnnotatedString {
                        append(activeName.ifBlank { audioLabel })
                        if (syncCount > 0) {
                            // En-space at full size — a touch more air than a plain
                            // space (which would shrink with the badge span).
                            append("\u2002")
                            withStyle(
                                SpanStyle(
                                    fontSize = metrics.headChipTextSp * 0.78f,
                                    color = RailInk.copy(alpha = 0.62f),
                                    baselineShift = BaselineShift(0.08f),
                                ),
                            ) {
                                append("+$syncCount")
                            }
                        }
                    },
                    color = RailInk,
                    fontSize = metrics.headChipTextSp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (openPlaylist != null) {
            val pl = openPlaylist!!
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                RailPlaylistDetailPage(
                    seed = pl,
                    manager = manager,
                    onBack = { openPlaylist = null },
                    onPlayed = { name ->
                        toast = name
                        openPlaylist = null
                        tab = RailTab.UpNext
                    },
                    onPlayFailed = { toast = failedLabel },
                )
            }
        } else if (sub == RailSub.None) {
            RailSegmentedTabs(tab = tab, onTab = { tab = it })
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    RailTab.UpNext -> RailQueuePage(
                        items = queueItems,
                        waitingIntro = waitingForMedia,
                        onPlayIndex = { idx ->
                            manager?.playQueueIndex(idx) == true
                        },
                        onMoveItem = { itemId, posShift ->
                            scope.launch {
                                manager?.moveQueueItem(itemId, posShift)
                            }
                        },
                        onFavoriteCurrent = { queueItemId ->
                            scope.launch {
                                val (ok, wasFavorite) =
                                    manager?.toggleQueueItemFavorite(queueItemId)
                                        ?: (false to false)
                                toast = when {
                                    !ok -> failedLabel
                                    wasFavorite ->
                                        context.getString(R.string.mass_api_rail_unfavorited)
                                    else ->
                                        context.getString(R.string.mass_api_rail_favorited)
                                }
                            }
                        },
                    )
                    RailTab.Library -> RailLibraryPage(
                        playlists = playlists,
                        searchQuery = searchQuery,
                        searchResults = searchResults,
                        searchBusy = searchBusy,
                        playPendingKey = searchPlayKey,
                        playBusy = searchPlayBusy,
                        onQueryChange = { q ->
                            searchQuery = q
                            manager?.searchAsync(q, tracksOnly = searchAddToQueue)
                        },
                        onImeFocus = { wanted ->
                            VinylCoverService.setRailSearchImeFocus(wanted)
                        },
                        onNotice = { toast = it },
                        onOpen = { openPlaylist = it },
                        onPlayResult = { hit ->
                            if (searchPlayBusy || manager == null) return@RailLibraryPage
                            if (searchAddToQueue && !hit.isTrack) {
                                toast = context.getString(
                                    R.string.mass_api_rail_search_add_tracks_only,
                                )
                                return@RailLibraryPage
                            }
                            searchPlayBusy = true
                            searchPlayKey = hit.stableKey
                            scope.launch {
                                try {
                                    val ok = runCatching {
                                        if (searchAddToQueue || hit.isTrack) {
                                            manager?.playTrackUri(
                                                hit.uri,
                                                replaceIfPlayRejected = !searchAddToQueue,
                                            ) == true
                                        } else {
                                            manager?.playPlaylist(hit.uri) == true
                                        }
                                    }.getOrDefault(false)
                                    if (ok) {
                                        toast = hit.name
                                        // Leaving Library disposes the search field, which
                                        // hands the window focus flag back.
                                        tab = RailTab.UpNext
                                    } else {
                                        toast = failedLabel
                                    }
                                } finally {
                                    if (searchPlayKey == hit.stableKey) {
                                        searchPlayBusy = false
                                        searchPlayKey = null
                                    }
                                }
                            }
                        },
                    )
                    RailTab.Sync -> RailSyncHub(
                        onSyncDelay = { sub = RailSub.SyncDelay },
                        showEqualizer = railEqEnabled,
                        onEqualizer = { sub = RailSub.Equalizer },
                        onLyrics = { sub = RailSub.Lyrics },
                        onSpeakers = { sub = RailSub.Speakers },
                        onSleep = { sub = RailSub.Sleep },
                        onMore = { sub = RailSub.More },
                    )
                }
            }
        } else {
            when (sub) {
                RailSub.Audio -> RailAudioPage(
                    players = players,
                    activePlayerId = activePlayerId,
                    fallbackTrack = queueTrack,
                    onBack = { sub = RailSub.None },
                    onSaveSync = { members ->
                        scope.launch {
                            val ok = manager?.saveSyncMembers(members) == true
                            toast = if (ok) {
                                if (members.isEmpty()) {
                                    soloLabel
                                } else {
                                    context.getString(
                                        R.string.mass_api_rail_synced,
                                        members.size + 1,
                                    )
                                }
                            } else {
                                failedLabel
                            }
                            if (ok) sub = RailSub.None
                        }
                    },
                    onSwitchTo = { target ->
                        scope.launch {
                            val ok = manager?.switchPlaybackTo(target) == true
                            toast = if (ok) {
                                players.firstOrNull { it.playerId == target }?.displayName
                                    ?: target
                            } else {
                                failedLabel
                            }
                            if (ok) sub = RailSub.None
                        }
                    },
                )
                RailSub.Speakers -> RailSpeakersPage(
                    players = players,
                    activePlayerId = activePlayerId,
                    onBack = { sub = RailSub.None },
                    onGroupVolume = { level ->
                        scope.launch { manager?.setVolume(activePlayerId, level, group = true) }
                    },
                    onMemberVolume = { id, level ->
                        scope.launch { manager?.setVolume(id, level, group = false) }
                    },
                )
                RailSub.SyncDelay -> RailSyncDelayPage(
                    players = players,
                    activePlayerId = activePlayerId,
                    onBack = { sub = RailSub.None },
                    onToast = { toast = it },
                )
                RailSub.Equalizer -> RailEqualizerPage(
                    players = players,
                    activePlayerId = activePlayerId,
                    onBack = { sub = RailSub.None },
                )
                RailSub.Lyrics -> RailLyricsPage(
                    players = players,
                    activePlayerId = activePlayerId,
                    onBack = { sub = RailSub.None },
                )
                RailSub.Sleep -> RailSleepPage(
                    sleepMinutes = sleepMinutes,
                    onBack = { sub = RailSub.None },
                    onPick = { m ->
                        manager?.setSleepMinutes(m)
                        toast = if (m > 0) {
                            context.getString(R.string.mass_api_rail_sleep_min, m)
                        } else {
                            offLabel
                        }
                        sub = RailSub.None
                    },
                )
                RailSub.More -> RailMorePage(
                    searchAddToQueue = searchAddToQueue,
                    autoplayEnabled = autoplayEnabled,
                    crossfadeEnabled = crossfadeEnabled,
                    syncFabExpand = syncFabExpand,
                    onBack = { sub = RailSub.None },
                    onSearchAddToQueue = { enabled ->
                        prefs.edit().putBoolean(KEY_RAIL_SEARCH_ADD_TO_QUEUE, enabled).apply()
                    },
                    onAutoplay = { enabled ->
                        scope.launch {
                            val ok = manager?.setAutoplayEnabled(enabled) == true
                            if (!ok) toast = failedLabel
                        }
                    },
                    onCrossfade = { enabled ->
                        scope.launch {
                            val ok = manager?.setCrossfadeEnabled(enabled) == true
                            if (!ok) toast = failedLabel
                        }
                    },
                    onSyncFabExpand = { enabled ->
                        prefs.edit().putBoolean(KEY_RAIL_SYNC_FAB_EXPAND, enabled).apply()
                    },
                    onClearQueue = {
                        scope.launch {
                            val ok = manager?.clearCurrentQueue() == true
                            if (!ok) toast = failedLabel
                        }
                    },
                )
                RailSub.None -> Unit
            }
        }

        toast?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = metrics.padV),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = msg,
                    color = RailOnLight,
                    fontSize = metrics.secondarySp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(RailInk.copy(alpha = 0.92f))
                        .padding(horizontal = metrics.headChipPadH, vertical = metrics.headChipPadV),
                )
            }
        }
    }
    }
}

@Composable
private fun RailSegmentedTabs(tab: RailTab, onTab: (RailTab) -> Unit) {
    val m = LocalMassRailMetrics.current
    val palette = LocalMassRailPalette.current
    val tabs = listOf(
        RailTab.UpNext to stringResource(R.string.mass_api_rail_tab_up_next),
        RailTab.Library to stringResource(R.string.mass_api_rail_tab_library),
        RailTab.Sync to stringResource(R.string.mass_api_rail_tab_sync),
    )
    val selectedIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    val inset = (3f * m.scale).coerceIn(2f, 5f).dp
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = m.padH)
            .padding(bottom = m.padV)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(inset),
    ) {
        val segmentWidth = maxWidth / tabs.size
        val pillOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "railTabPill",
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            // Sliding capsule indicator (not page content).
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = pillOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.accentSoft),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEach { (key, label) ->
                    val selected = tab == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onTab(key) }
                            .padding(vertical = m.tabPadV),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (selected) palette.onAccent else RailInk.copy(alpha = 0.72f),
                            fontSize = m.secondarySp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailQueuePage(
    items: List<MassQueueItem>,
    onPlayIndex: suspend (Int) -> Boolean,
    onMoveItem: (queueItemId: String, posShift: Int) -> Unit,
    onFavoriteCurrent: (queueItemId: String) -> Unit,
    /**
     * Cold-start waiting shell: the list is last session's leftover queue.
     * Render it compact (single-line rows); first tap eases it open. Once
     * open, rows play normally — play_index is a server-side queue command,
     * so it works even while the local player is still waiting for media.
     */
    waitingIntro: Boolean = false,
) {
    val m = LocalMassRailMetrics.current
    val scope = rememberCoroutineScope()
    if (items.isEmpty()) {
        RailEmpty(stringResource(R.string.mass_api_rail_empty_audio))
        return
    }

    var introExpanded by remember { mutableStateOf(false) }
    // Auto-eases open when real media arrives (waitingIntro flips false).
    val compactIntro = waitingIntro && !introExpanded

    // Local order while dragging (Quick Entity / sidebar pattern).
    var localItems by remember { mutableStateOf(items) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    /** Tap-to-play wait: spinner until playQueueIndex returns (or timeout). */
    var pendingPlayId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(items) {
        if (draggingId == null) {
            localItems = items
        }
    }

    // Safety: clear hung wait if RPC never returns.
    LaunchedEffect(pendingPlayId) {
        val id = pendingPlayId ?: return@LaunchedEffect
        delay(8_000)
        if (pendingPlayId == id) pendingPlayId = null
    }

    val currentIdx = localItems.indexOfFirst { it.isCurrent }
    // MA rejects moves at/before the buffered/current head.
    val minReorderIdx = if (currentIdx >= 0) currentIdx + 1 else 0

    val listState = rememberLazyListState()
    // A: only when this page opens (remember resets on leave). D: skip if current
    // already visible — never chase currentId on tap / auto-next while open.
    var didOpenScroll by remember { mutableStateOf(false) }
    LaunchedEffect(currentIdx, localItems.size) {
        if (didOpenScroll || draggingId != null) return@LaunchedEffect
        if (currentIdx < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .first { it.isNotEmpty() }
        if (listState.layoutInfo.visibleItemsInfo.any { it.index == currentIdx }) {
            didOpenScroll = true
            return@LaunchedEffect
        }
        didOpenScroll = true
        // Seat current at slot 2 (one song above). Near targets animate the whole
        // way; far ones teleport to one viewport above so animateScrollToItem never
        // walks the skipped rows (the jank at index 40+).
        val settleIndex = (currentIdx - 1).coerceAtLeast(0)
        val fromIndex = listState.firstVisibleItemIndex
        if (settleIndex - fromIndex > QUEUE_OPEN_SCROLL_TAIL_ROWS) {
            listState.scrollToItem(
                (settleIndex - QUEUE_OPEN_SCROLL_TAIL_ROWS).coerceAtLeast(0),
            )
            yield()
        }
        listState.animateScrollToItem(settleIndex)
    }

    fun finishDrag(commit: Boolean) {
        val id = draggingId ?: return
        val fromIndex = localItems.indexOfFirst { it.queueItemId == id }
        if (fromIndex >= 0 && rowHeightPx > 0f) {
            val displacement = (dragOffsetY / rowHeightPx).roundToInt()
            val toIndex = (fromIndex + displacement).coerceIn(minReorderIdx, localItems.lastIndex)
            if (commit && fromIndex != toIndex) {
                val reordered = localItems.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                localItems = reordered
                onMoveItem(id, toIndex - fromIndex)
            }
        }
        draggingId = null
        dragOffsetY = 0f
    }

    val dropTargetIndex = if (draggingId != null && rowHeightPx > 0f) {
        val from = localItems.indexOfFirst { it.queueItemId == draggingId }
        if (from >= 0) {
            (from + (dragOffsetY / rowHeightPx).roundToInt())
                .coerceIn(minReorderIdx, localItems.lastIndex)
        } else {
            -1
        }
    } else {
        -1
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = m.padH),
        verticalArrangement = Arrangement.spacedBy((2f * m.scale).dp),
        userScrollEnabled = draggingId == null,
    ) {
        itemsIndexed(localItems, key = { _, it -> it.queueItemId }) { index, item ->
            val on = item.isCurrent
            val pending = pendingPlayId == item.queueItemId
            val playBusy = pendingPlayId != null
            val canReorder = index >= minReorderIdx
            val isDragging = draggingId == item.queueItemId
            val isDropTarget =
                dropTargetIndex == index && draggingId != null && !isDragging
            // Compact intro: tighter rows + no artist line; eases open on tap.
            val introRowPadV by animateDpAsState(
                targetValue = if (compactIntro) m.rowPadV * 0.4f else m.rowPadV,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                label = "introRowPadV",
            )
            val introHandleH by animateDpAsState(
                targetValue = if (compactIntro) {
                    (30f * m.scale).coerceIn(26f, 38f).dp
                } else {
                    (44f * m.scale).coerceIn(40f, 56f).dp
                },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                label = "introHandleH",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 2f else 0f)
                    .onGloballyPositioned { coords ->
                        // Track live height — the compact intro animates row size.
                        rowHeightPx = coords.size.height.toFloat()
                    }
                    .graphicsLayer {
                        // Quick Entity float: ~1.1× lift while dragging.
                        translationY = if (isDragging) dragOffsetY else 0f
                        val scale = if (isDragging) 1.08f else 1f
                        scaleX = scale
                        scaleY = scale
                        // Past rows (above current): slightly dimmed so now-playing pops.
                        alpha = when {
                            isDragging -> 0.92f
                            isDropTarget -> 0.75f
                            pending -> 1f
                            currentIdx >= 0 && index < currentIdx -> 0.58f
                            playBusy && !pending -> 0.72f
                            else -> 1f
                        }
                        shadowElevation = if (isDragging) 12f else 0f
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(m.cornerMd))
                        .then(
                            when {
                                isDropTarget -> Modifier
                                    .background(LocalMassRailPalette.current.accent.copy(alpha = 0.18f))
                                    .border(
                                        1.dp,
                                        LocalMassRailPalette.current.accent.copy(alpha = 0.45f),
                                        RoundedCornerShape(m.cornerMd),
                                    )
                                pending -> Modifier
                                    .background(LocalMassRailPalette.current.accent.copy(alpha = 0.10f))
                                    .border(
                                        1.dp,
                                        LocalMassRailPalette.current.accent.copy(alpha = 0.28f),
                                        RoundedCornerShape(m.cornerMd),
                                    )
                                // Now-playing: soft white chrome — not theme gray/brown.
                                on -> Modifier
                                    .background(RailInk.copy(alpha = 0.12f))
                                    .border(
                                        1.dp,
                                        RailInk.copy(alpha = 0.28f),
                                        RoundedCornerShape(m.cornerMd),
                                    )
                                else -> Modifier
                            },
                        )
                        .clickable(
                            // Compact intro: tappable to expand. Expanded (waiting
                            // included): every non-current row taps to play. While
                            // waiting, isCurrent is masked upstream, so all rows play.
                            enabled = !isDragging && !playBusy && (compactIntro || !on),
                        ) {
                            if (compactIntro) {
                                // First tap only eases the compact list open.
                                introExpanded = true
                                return@clickable
                            }
                            pendingPlayId = item.queueItemId
                            scope.launch {
                                runCatching { onPlayIndex(index) }
                                if (pendingPlayId == item.queueItemId) {
                                    pendingPlayId = null
                                }
                            }
                        }
                        .padding(horizontal = m.rowPadH, vertical = introRowPadV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(m.indexWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(
                                    (14f * m.scale).coerceIn(12f, 18f).dp,
                                ),
                                strokeWidth = (1.6f * m.scale).coerceIn(1.2f, 2f).dp,
                                color = LocalMassRailPalette.current.accent,
                                trackColor = RailInk.copy(alpha = 0.12f),
                            )
                        } else {
                            Text(
                                text = "%02d".format(index + 1),
                                color = when {
                                    isDropTarget -> LocalMassRailPalette.current.accent
                                    on -> RailInk
                                    else -> RailFaint
                                },
                                fontSize = m.secondarySp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = m.rowPadH)) {
                        Text(
                            text = item.title,
                            color = if (isDropTarget || pending) {
                                LocalMassRailPalette.current.accent
                            } else {
                                RailInk
                            },
                            fontSize = m.titleSp,
                            fontWeight = if (on || pending) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AnimatedVisibility(
                            visible = !compactIntro && item.artist.isNotBlank(),
                            enter = fadeIn(tween(240)) + expandVertically(tween(240)),
                            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                        ) {
                            Text(
                                text = item.artist,
                                color = RailMuted,
                                fontSize = m.secondarySp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = (3f * m.scale).dp),
                            )
                        }
                    }
                    // Current track cannot reorder — show favorite heart instead of grip.
                    // Other rows keep the long-press drag handle.
                    // While pending, optimistic isCurrent may already flip — keep heart
                    // path only for settled current without an in-flight tap.
                    if (on && !pending) {
                        val canFavorite = item.favoriteItemUri().isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(
                                    width = (36f * m.scale).coerceIn(32f, 48f).dp,
                                    height = (44f * m.scale).coerceIn(40f, 56f).dp,
                                )
                                .clickable(enabled = canFavorite) {
                                    // Nested clickable — does not fire row play.
                                    // Toggle: add when empty, remove when filled.
                                    onFavoriteCurrent(item.queueItemId)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            RailIcon(
                                imageVector = if (item.favorite) {
                                    RailHeartFilledIcon
                                } else {
                                    RailHeartOutlineIcon
                                },
                                contentDescription = stringResource(
                                    if (item.favorite) {
                                        R.string.mass_api_rail_unfavorite
                                    } else {
                                        R.string.mass_api_rail_favorite
                                    },
                                ),
                                size = (20f * m.scale).coerceIn(18f, 26f).dp,
                                // Rail ink only — filled vs outline carries state, not a louder tint.
                                tint = if (canFavorite) {
                                    RailInk.copy(alpha = 0.45f)
                                } else {
                                    RailInk.copy(alpha = 0.2f)
                                },
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(
                                    width = (36f * m.scale).coerceIn(32f, 48f).dp,
                                    height = introHandleH,
                                )
                                .then(
                                    if (canReorder && !playBusy) {
                                        Modifier.pointerInput(item.queueItemId) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingId = item.queueItemId
                                                    dragOffsetY = 0f
                                                },
                                                onDrag = { _, dragAmount ->
                                                    if (draggingId == item.queueItemId) {
                                                        dragOffsetY += dragAmount.y
                                                    }
                                                },
                                                onDragEnd = { finishDrag(commit = true) },
                                                onDragCancel = { finishDrag(commit = false) },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier.width((18f * m.scale).dp),
                                verticalArrangement = Arrangement.spacedBy((2f * m.scale).dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height((1.5f * m.scale).dp)
                                            .background(
                                                when {
                                                    !canReorder || playBusy ->
                                                        RailInk.copy(alpha = 0.08f)
                                                    isDragging ->
                                                        LocalMassRailPalette.current.accent
                                                    else -> RailInk.copy(alpha = 0.28f)
                                                },
                                                RoundedCornerShape(99.dp),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Library: search field, then either the hits or the playlist list.
 *
 * The field sits at the very top deliberately. The host window resizes for the keyboard
 * (SOFT_INPUT_ADJUST_RESIZE), so the rail loses height from the bottom — putting the field
 * first keeps it on screen and lets the results list absorb the shrink instead.
 */
@Composable
private fun RailLibraryPage(
    playlists: List<MassPlaylist>,
    searchQuery: String,
    searchResults: List<MassSearchItem>,
    searchBusy: Boolean,
    playPendingKey: String?,
    playBusy: Boolean,
    onQueryChange: (String) -> Unit,
    onImeFocus: (Boolean) -> Unit,
    onNotice: (String) -> Unit,
    onOpen: (MassPlaylist) -> Unit,
    onPlayResult: (MassSearchItem) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    Column(modifier = Modifier.fillMaxSize()) {
        RailSearchBar(
            query = searchQuery,
            busy = searchBusy,
            onQueryChange = onQueryChange,
            onImeFocus = onImeFocus,
            onNotice = onNotice,
            modifier = Modifier.padding(horizontal = m.padH, vertical = m.gapSm),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (searchQuery.isNotBlank()) {
                RailSearchResults(
                    query = searchQuery,
                    results = searchResults,
                    busy = searchBusy,
                    playPendingKey = playPendingKey,
                    playBusy = playBusy,
                    onPlay = onPlayResult,
                )
            } else {
                RailPlaylistList(playlists = playlists, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun RailPlaylistList(
    playlists: List<MassPlaylist>,
    onOpen: (MassPlaylist) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    if (playlists.isEmpty()) {
        RailEmpty(stringResource(R.string.mass_api_rail_no_playlists))
        return
    }
    val playlistFallback = stringResource(R.string.mass_api_rail_playlist)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = m.padH),
    ) {
        itemsIndexed(playlists, key = { _, it -> it.itemId }) { _, pl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.cornerLg))
                    // Open detail only — never inject / play from the list row.
                    .clickable { onOpen(pl) }
                    .padding(vertical = m.rowPadV, horizontal = m.gapSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(m.tileSize)
                        .clip(RoundedCornerShape(m.cornerMd))
                        .background(Color.White.copy(alpha = 0.07f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // List stays cover-free (lazy art only after entering detail).
                    RailIcon(
                        imageVector = Icons.Filled.List,
                        contentDescription = null,
                        size = m.iconMd,
                        tint = RailInk.copy(alpha = 0.7f),
                    )
                }
                // End inset keeps long ellipsized names clear of the › (its -5dp
                // optical offset draws into the text lane, so reserve gapMd + 5dp).
                Column(
                    modifier = Modifier
                        .padding(start = m.padH, end = m.gapMd + 5.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = pl.name,
                        color = RailInk,
                        fontSize = m.titleSp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pl.trackCount?.let {
                            stringResource(R.string.mass_api_rail_tracks_count, it)
                        } ?: pl.provider.ifBlank { playlistFallback },
                        color = RailMuted,
                        fontSize = m.secondarySp,
                        modifier = Modifier.padding(top = (3f * m.scale).dp),
                    )
                }
                // Same optical nudge as HubCard — › carries a wide right bearing.
                Text(
                    text = "›",
                    color = RailInk.copy(alpha = 0.45f),
                    fontSize = m.chevronSp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.offset(x = (-5).dp),
                )
            }
        }
    }
}

/**
 * Search field for the rail.
 *
 * Ordering is the whole trick here. The host overlay window is FLAG_NOT_FOCUSABLE and an IME
 * cannot attach to a window that cannot take focus, so a tap has to lift that flag *first*,
 * let the WindowManager round-trip land, and only then move editor focus and ask for the
 * keyboard. Requesting focus first leaves a caret blinking with no keyboard behind it.
 *
 * The flag is borrowed, never kept: blur and disposal both hand it straight back, because a
 * focusable media overlay keeps swallowing Back and stealing focus from the voice layers.
 */
@Composable
private fun RailSearchBar(
    query: String,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onImeFocus: (Boolean) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = LocalMassRailMetrics.current
    val accent = LocalMassRailPalette.current.accent
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val voiceUnavailable = stringResource(R.string.mass_api_rail_voice_unavailable)

    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        onImeFocus(true)
        delay(IME_WINDOW_SETTLE_MS)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    // Only collect transcripts while the mic button is armed: an unrelated wake-word
    // conversation must never rewrite the search box behind the user's back. searchListen
    // is the STT-only branch so the collector attaches before HA can answer.
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        val heard = withTimeoutOrNull(VOICE_SEARCH_TIMEOUT_MS) {
            VoiceSatelliteService.sttText
                .onSubscription { VoiceSatelliteService.searchListen() }
                .first()
        }
        listening = false
        // Upstream STT often appends a sentence period (。 / .); strip it so the
        // library search query stays clean.
        heard?.trim()
            ?.trimEnd('.', '。', '．')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(onQueryChange)
    }
    DisposableEffect(Unit) {
        // Leaving the tab, closing the rail or collapsing to the FAB all land here.
        onDispose { onImeFocus(false) }
    }

    fun release() {
        armed = false
        keyboard?.hide()
        onImeFocus(false)
    }

    fun toggleVoice() {
        if (listening) {
            listening = false
            VoiceSatelliteService.stopVoiceSession()
            return
        }
        if (!VoiceSatelliteService.isSatelliteStarted()) {
            onNotice(voiceUnavailable)
            return
        }
        // Voice replaces typing — put the keyboard away and hand the window focus back before
        // the pipeline takes over.
        release()
        // Arming starts the collector, which fires the STT-only search-listen branch.
        // That reuses the HA uplink but stops at STT_END — no wake orb, captions, or TTS.
        listening = true
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.cornerLg))
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.06f))
            .border(
                width = 1.dp,
                color = if (focused) accent.copy(alpha = 0.45f) else RailLine,
                shape = RoundedCornerShape(m.cornerLg),
            )
            .clickable {
                armed = true
                onImeFocus(true)
            }
            .padding(horizontal = m.rowPadH, vertical = m.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailIcon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            size = m.iconSm,
            tint = if (focused) accent else RailInk.copy(alpha = 0.55f),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = m.gapMd),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = RailInk, fontSize = m.bodySp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Nothing to submit: results already stream in as the query settles, so the
                // action key just puts the keyboard away.
                keyboardActions = KeyboardActions(onSearch = { release() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        focused = state.isFocused
                        if (state.isFocused) {
                            armed = true
                            onImeFocus(true)
                            keyboard?.show()
                        } else if (armed) {
                            release()
                        }
                    },
            )
            if (query.isEmpty()) {
                Text(
                    text = if (listening) {
                        stringResource(R.string.mass_api_rail_voice_listening)
                    } else {
                        stringResource(R.string.mass_api_rail_search_placeholder)
                    },
                    color = if (listening) accent.copy(alpha = 0.75f) else RailFaint,
                    fontSize = m.bodySp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size((14f * m.scale).coerceIn(12f, 18f).dp),
                strokeWidth = (1.6f * m.scale).coerceIn(1.2f, 2f).dp,
                color = accent,
                trackColor = RailInk.copy(alpha = 0.12f),
            )
        } else if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        onQueryChange("")
                        release()
                    }
                    .padding((4f * m.scale).coerceIn(3f, 7f).dp),
            ) {
                RailIcon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.mass_api_rail_search_clear),
                    size = m.iconSm,
                    tint = RailInk.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(modifier = Modifier.width(m.gapSm))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    if (listening) {
                        Modifier.background(accent.copy(alpha = 0.18f))
                    } else {
                        Modifier
                    },
                )
                .clickable { toggleVoice() }
                .padding((4f * m.scale).coerceIn(3f, 7f).dp),
        ) {
            RailIcon(
                imageVector = Icons.Filled.Mic,
                contentDescription = stringResource(R.string.mass_api_rail_voice_search),
                size = m.iconSm,
                tint = if (listening) accent else RailInk.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun RailSearchResults(
    query: String,
    results: List<MassSearchItem>,
    busy: Boolean,
    playPendingKey: String?,
    playBusy: Boolean,
    onPlay: (MassSearchItem) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    if (results.isEmpty()) {
        RailEmpty(
            when {
                busy -> stringResource(R.string.mass_api_rail_searching)
                query.trim().length < MassApiManager.SEARCH_MIN_CHARS ->
                    stringResource(
                        R.string.mass_api_rail_search_min_chars,
                        MassApiManager.SEARCH_MIN_CHARS,
                    )
                else -> stringResource(R.string.mass_api_rail_search_empty)
            },
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = m.padH),
    ) {
        itemsIndexed(results, key = { _, hit -> hit.stableKey }) { _, hit ->
            RailSearchResultRow(
                hit = hit,
                pending = playPendingKey == hit.stableKey,
                busy = playBusy,
                onPlay = onPlay,
            )
        }
    }
}

/** Mirrors the playlist-detail track row, with a type glyph where the index would be. */
@Composable
private fun RailSearchResultRow(
    hit: MassSearchItem,
    pending: Boolean,
    busy: Boolean,
    onPlay: (MassSearchItem) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val accent = LocalMassRailPalette.current.accent
    val rowPadV = (10f * m.scale).coerceIn(8f, 16f).dp
    val titleMax = (m.titleSp.value * 1.06f).coerceIn(15f, 24f).sp
    val subMax = (m.bodySp.value * 1.04f).coerceIn(13f, 20f).sp
    val count = hit.trackCount
    val subtitle = when {
        hit.artist.isNotBlank() -> hit.artist
        count != null -> stringResource(R.string.mass_api_rail_tracks_count, count)
        else -> hit.provider
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.cornerMd))
            .then(
                if (pending) {
                    Modifier
                        .background(accent.copy(alpha = 0.10f))
                        .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(m.cornerMd))
                } else {
                    Modifier
                },
            )
            .graphicsLayer { alpha = if (!pending && busy) 0.72f else 1f }
            // Single-flight, same as the playlist rows.
            .clickable(enabled = !busy) { onPlay(hit) }
            .padding(horizontal = m.rowPadH, vertical = rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(m.indexWidth), contentAlignment = Alignment.Center) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size((14f * m.scale).coerceIn(12f, 18f).dp),
                    strokeWidth = (1.6f * m.scale).coerceIn(1.2f, 2f).dp,
                    color = accent,
                    trackColor = RailInk.copy(alpha = 0.12f),
                )
            } else {
                RailIcon(
                    imageVector = when (hit.mediaType) {
                        "album" -> Icons.Filled.Album
                        "playlist" -> Icons.Filled.List
                        else -> Icons.Filled.MusicNote
                    },
                    contentDescription = null,
                    size = m.iconSm,
                    tint = RailInk.copy(alpha = 0.55f),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.gapMd),
        ) {
            RailAutoFitText(
                text = hit.name,
                color = if (pending) accent else RailInk,
                maxFontSize = titleMax,
                minFontSize = m.captionSp,
                fontWeight = if (pending) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                RailAutoFitText(
                    text = subtitle,
                    color = RailMuted,
                    maxFontSize = subMax,
                    minFontSize = (11f * m.scale).coerceIn(10f, 14f).sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(top = (3f * m.scale).dp),
                )
            }
        }
    }
}

/**
 * Playlist detail: cover (lazy) + name/desc, then own tracks.
 * Tracks admit in small UI chunks; next MA page only after the user hits bottom
 * and any local overflow buffer is drained. Play injects only via the button.
 */
@Composable
private fun RailPlaylistDetailPage(
    seed: MassPlaylist,
    manager: MassApiManager?,
    onBack: () -> Unit,
    onPlayed: (name: String) -> Unit,
    onPlayFailed: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val scope = rememberCoroutineScope()
    var detail by remember(seed.itemId) { mutableStateOf(seed) }
    var tracks by remember(seed.itemId) { mutableStateOf<List<MassPlaylistTrack>>(emptyList()) }
    var loadingMore by remember(seed.itemId) { mutableStateOf(false) }
    var initialLoad by remember(seed.itemId) { mutableStateOf(true) }
    var exhausted by remember(seed.itemId) { mutableStateOf(false) }
    var playingBusy by remember(seed.itemId) { mutableStateOf(false) }
    /** Null = whole-playlist play; non-null = that track’s row wait. */
    var playingTrackKey by remember(seed.itemId) { mutableStateOf<String?>(null) }
    val pending = remember(seed.itemId) { ArrayDeque<MassPlaylistTrack>() }
    val seenKeys = remember(seed.itemId) { linkedSetOf<String>() }
    var nextServerPage by remember(seed.itemId) { mutableIntStateOf(0) }
    var serverDone by remember(seed.itemId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coverSide = (72f * m.scale).coerceIn(56f, 96f).dp

    suspend fun admitNextChunk() {
        if (manager == null || loadingMore || exhausted) return
        loadingMore = true
        try {
            // Drain local overflow first — never hit the network while buffer has rows.
            if (pending.isNotEmpty()) {
                val chunk = buildList<MassPlaylistTrack> {
                    repeat(PLAYLIST_UI_CHUNK) {
                        pending.pollFirst()?.let(::add)
                    }
                }
                if (chunk.isNotEmpty()) tracks = tracks + chunk
                if (pending.isEmpty() && serverDone) exhausted = true
                return
            }
            if (serverDone) {
                exhausted = true
                return
            }
            // Soft pacing after the first page so bottom-fling cannot hammer MA.
            if (nextServerPage > 0) {
                kotlinx.coroutines.delay(PLAYLIST_PAGE_GAP_MS)
            }
            val page = manager.fetchPlaylistTracksPage(detail, nextServerPage)
            nextServerPage += 1
            if (page.isEmpty()) {
                serverDone = true
                exhausted = pending.isEmpty()
                return
            }
            var added = 0
            for (t in page) {
                if (seenKeys.add(t.stableKey)) {
                    pending.addLast(t)
                    added++
                }
            }
            if (added == 0) {
                // Duplicate / stuck page — stop rather than loop forever.
                serverDone = true
                exhausted = pending.isEmpty()
                return
            }
            val chunk = buildList<MassPlaylistTrack> {
                repeat(PLAYLIST_UI_CHUNK) {
                    pending.pollFirst()?.let(::add)
                }
            }
            if (chunk.isNotEmpty()) tracks = tracks + chunk
            if (pending.isEmpty() && serverDone) exhausted = true
        } finally {
            loadingMore = false
            initialLoad = false
        }
    }

    LaunchedEffect(seed.itemId, manager) {
        if (manager == null) {
            initialLoad = false
            return@LaunchedEffect
        }
        // Header (description + cover URL) only after enter — list never hydrated art.
        val rich = manager.fetchPlaylistDetail(seed)
        if (rich != null) detail = rich
        admitNextChunk()
    }

    // Bottom reach → next UI chunk / next MA page.
    LaunchedEffect(listState, tracks.size, exhausted, loadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total > 0 && last >= total - 2
        }
            .distinctUntilChanged()
            .collect { nearBottom ->
                if (nearBottom && !exhausted && !loadingMore && tracks.isNotEmpty()) {
                    admitNextChunk()
                }
            }
    }

    // Safety: clear hung play wait if RPC never returns.
    LaunchedEffect(playingBusy, playingTrackKey) {
        if (!playingBusy) return@LaunchedEffect
        val key = playingTrackKey
        delay(8_000)
        if (playingBusy && playingTrackKey == key) {
            playingBusy = false
            playingTrackKey = null
        }
    }

    fun startPlay(
        trackKey: String?,
        block: suspend () -> Boolean,
        successName: String,
    ) {
        if (playingBusy || manager == null) return
        playingBusy = true
        playingTrackKey = trackKey
        scope.launch {
            try {
                val ok = runCatching { block() }.getOrDefault(false)
                if (ok) onPlayed(successName) else onPlayFailed()
            } finally {
                if (playingTrackKey == trackKey) {
                    playingBusy = false
                    playingTrackKey = null
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val playPlaylistLabel = stringResource(R.string.mass_api_rail_play_playlist)
        val canPlayPlaylist = !playingBusy &&
            manager != null &&
            (detail.uri.isNotBlank() || detail.itemId.isNotBlank())
        RailSubBar(
            title = stringResource(R.string.mass_api_rail_playlist),
            onBack = onBack,
            trailing = {
                // Same chip style as Audio Transfer / Swap — right-aligned in the sub-nav.
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = if (canPlayPlaylist) 1f else 0.4f
                    },
                ) {
                    RailChipBtn(
                        label = playPlaylistLabel,
                        icon = Icons.Filled.PlayArrow,
                        active = false,
                        onClick = {
                            if (!canPlayPlaylist) return@RailChipBtn
                            val uri = detail.uri.ifBlank { detail.itemId }
                            if (uri.isBlank() || manager == null) return@RailChipBtn
                            startPlay(
                                trackKey = null,
                                block = { manager.playPlaylist(uri) },
                                successName = detail.name,
                            )
                        },
                    )
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = m.padH),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Air under the 「歌单」 sub-nav so cover/title are not jammed into it.
                        .padding(top = m.gapMd + m.padV, bottom = m.gapMd),
                    verticalAlignment = Alignment.Top,
                ) {
                    RailLazyPlaylistCover(
                        imageUrl = detail.imageUrl,
                        side = coverSide,
                        corner = m.cornerMd,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            // Extra air between cover and title/description.
                            .padding(start = m.padH + m.gapMd),
                    ) {
                        // One line: shrink to fit the column width on any screen size.
                        RailAutoFitText(
                            text = detail.name,
                            color = RailInk,
                            maxFontSize = m.hubTitleSp,
                            minFontSize = m.captionSp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        val desc = detail.description.trim().ifEmpty {
                            stringResource(R.string.mass_api_rail_no_description)
                        }
                        // Two lines max; shrink first, then ellipsis the rest.
                        RailAutoFitText(
                            text = desc,
                            color = RailMuted,
                            maxFontSize = m.secondarySp,
                            minFontSize = (11f * m.scale).coerceIn(10f, 14f).sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 2,
                            modifier = Modifier.padding(top = (4f * m.scale).dp),
                        )
                    }
                }
            }
            if (tracks.isEmpty() && !loadingMore && !initialLoad) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.mass_api_rail_playlist_empty),
                        color = RailFaint,
                        fontSize = m.bodySp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = m.padV),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.stableKey }) { index, track ->
                val trackUri = track.uri.ifBlank {
                    track.itemId.takeIf { it.contains("://") }.orEmpty()
                }
                val trackPending = playingTrackKey == track.stableKey
                val canPlayTrack = trackUri.isNotBlank() && !playingBusy && manager != null
                // Slightly roomier than queue rows — easier taps; type scales with screen.
                val trackRowPadV = (10f * m.scale).coerceIn(8f, 16f).dp
                val indexW = (m.indexWidth.value * 1.1f).coerceIn(28f, 44f).dp
                val trackTitleMax = (m.titleSp.value * 1.06f).coerceIn(15f, 24f).sp
                val trackArtistMax = (m.bodySp.value * 1.04f).coerceIn(13f, 20f).sp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(m.cornerMd))
                        .then(
                            if (trackPending) {
                                Modifier
                                    .background(LocalMassRailPalette.current.accent.copy(alpha = 0.10f))
                                    .border(
                                        1.dp,
                                        LocalMassRailPalette.current.accent.copy(alpha = 0.28f),
                                        RoundedCornerShape(m.cornerMd),
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .graphicsLayer {
                            alpha = when {
                                trackPending -> 1f
                                playingBusy -> 0.72f
                                else -> 1f
                            }
                        }
                        // Whole row play — single-flight; ignore blank URI / in-flight.
                        .clickable(enabled = canPlayTrack) {
                            startPlay(
                                trackKey = track.stableKey,
                                block = { manager?.playTrackUri(trackUri) == true },
                                successName = track.name,
                            )
                        }
                        .padding(horizontal = m.rowPadH, vertical = trackRowPadV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(indexW),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (trackPending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(
                                    (14f * m.scale).coerceIn(12f, 18f).dp,
                                ),
                                strokeWidth = (1.6f * m.scale).coerceIn(1.2f, 2f).dp,
                                color = LocalMassRailPalette.current.accent,
                                trackColor = RailInk.copy(alpha = 0.12f),
                            )
                        } else {
                            Text(
                                text = String.format("%02d", index + 1),
                                color = RailFaint,
                                fontSize = m.secondarySp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = m.gapMd),
                    ) {
                        // Grow with screen; shrink to one line when the pane is narrow.
                        RailAutoFitText(
                            text = track.name,
                            color = if (trackPending) {
                                LocalMassRailPalette.current.accent
                            } else {
                                RailInk
                            },
                            maxFontSize = trackTitleMax,
                            minFontSize = m.captionSp,
                            fontWeight = if (trackPending) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        if (track.artist.isNotBlank()) {
                            RailAutoFitText(
                                text = track.artist,
                                color = RailMuted,
                                maxFontSize = trackArtistMax,
                                minFontSize = (11f * m.scale).coerceIn(10f, 14f).sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.padding(top = (3f * m.scale).dp),
                            )
                        }
                    }
                }
            }
            if (loadingMore || initialLoad) {
                item(key = "loading") {
                    Text(
                        text = stringResource(R.string.mass_api_rail_loading_more),
                        color = RailFaint,
                        fontSize = m.secondarySp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = m.padV),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Detail-only cover decode — never runs on the library list. */
@Composable
private fun RailLazyPlaylistCover(
    imageUrl: String,
    side: Dp,
    corner: Dp,
) {
    val m = LocalMassRailMetrics.current
    val maxSidePx = (side.value * 2f).toInt().coerceIn(96, 256)
    // Seed from the memory cache synchronously — rows re-entering composition on
    // scroll must not flash the placeholder for an IO round-trip they don't need.
    var bitmap by remember(imageUrl, maxSidePx) {
        mutableStateOf(peekRailCoverCache(imageUrl, maxSidePx))
    }
    LaunchedEffect(imageUrl, maxSidePx) {
        if (bitmap != null) return@LaunchedEffect
        val url = imageUrl.trim()
        if (url.isBlank() || !url.startsWith("http")) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            decodeRailCoverBitmap(url, maxSidePx = maxSidePx)
        }
    }
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(corner))
            .background(Color.White.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            RailIcon(
                imageVector = Icons.Filled.List,
                contentDescription = null,
                size = m.iconLg,
                tint = RailInk.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * Small in-memory cache for detail-page covers. LazyColumn rows leave and
 * re-enter composition on scroll, and every miss used to cost a fresh HTTP
 * download. Evicted bitmaps are NOT recycled (a painter may still draw them);
 * eviction just drops the reference for GC.
 */
private const val RAIL_COVER_CACHE_BYTES = 4 * 1024 * 1024
private val railCoverCache =
    object : android.util.LruCache<String, Bitmap>(RAIL_COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

/** Synchronous cache probe for composition-time seeding (no IO on miss). */
private fun peekRailCoverCache(url: String, maxSidePx: Int): Bitmap? =
    railCoverCache.get("$maxSidePx|${url.trim()}")?.takeUnless { it.isRecycled }

private fun decodeRailCoverBitmap(url: String, maxSidePx: Int): Bitmap? {
    val cacheKey = "$maxSidePx|$url"
    railCoverCache.get(cacheKey)?.takeUnless { it.isRecycled }?.let { return it }
    return try {
        // Single fetch — bounds and pixels both decode from the same bytes
        // (previously each decode pass opened its own connection).
        val bytes = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            inputStream.use { it.readBytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var bw = max(1, bounds.outWidth)
        var bh = max(1, bounds.outHeight)
        while (bw / 2 >= maxSidePx && bh / 2 >= maxSidePx) {
            sample *= 2
            bw /= 2
            bh /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?.also { railCoverCache.put(cacheKey, it) }
    } catch (e: Exception) {
        Log.w("MassApiOverlayRail", "playlist cover decode failed: $url", e)
        null
    }
}

@Composable
private fun RailSyncHub(
    onSyncDelay: () -> Unit,
    showEqualizer: Boolean,
    onEqualizer: () -> Unit,
    onLyrics: () -> Unit,
    onSpeakers: () -> Unit,
    onSleep: () -> Unit,
    onMore: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = m.padH)
            // Same breathing room as the sub-pages (Speakers / Sync Delay / More).
            .padding(top = m.gapSm, bottom = m.gapSm + 10.dp),
        verticalArrangement = Arrangement.spacedBy(m.gapMd),
    ) {
        HubCard(
            icon = {
                RailIcon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = null,
                    size = m.hubIcon,
                    tint = LocalMassRailPalette.current.accent,
                )
            },
            title = stringResource(R.string.mass_api_rail_speakers),
            subtitle = stringResource(R.string.mass_api_rail_speakers_subtitle),
            onClick = onSpeakers,
        )
        HubCard(
            icon = {
                RailIcon(
                    imageVector = RailSyncDelayIcon,
                    contentDescription = null,
                    size = m.hubIcon,
                    tint = LocalMassRailPalette.current.accent,
                )
            },
            title = stringResource(R.string.mass_api_rail_sync_delay),
            subtitle = stringResource(R.string.mass_api_rail_sync_delay_subtitle),
            onClick = onSyncDelay,
        )
        if (showEqualizer) {
            HubCard(
                icon = {
                    RailIcon(
                        imageVector = RailEqIcon,
                        contentDescription = null,
                        size = m.hubIcon,
                        tint = LocalMassRailPalette.current.accent,
                    )
                },
                title = stringResource(R.string.mass_api_rail_eq),
                subtitle = stringResource(R.string.mass_api_rail_eq_subtitle),
                onClick = onEqualizer,
            )
        }
        HubCard(
            icon = {
                RailIcon(
                    imageVector = RailLyricsIcon,
                    contentDescription = null,
                    size = m.hubIcon,
                    tint = LocalMassRailPalette.current.accent,
                )
            },
            title = stringResource(R.string.mass_api_rail_lyrics),
            subtitle = stringResource(R.string.mass_api_rail_lyrics_subtitle),
            onClick = onLyrics,
        )
        HubCard(
            icon = {
                RailIcon(
                    imageVector = RailMoonIcon,
                    contentDescription = null,
                    size = m.hubIcon,
                    tint = LocalMassRailPalette.current.accent,
                )
            },
            title = stringResource(R.string.mass_api_rail_sleep),
            subtitle = stringResource(R.string.mass_api_rail_sleep_subtitle),
            onClick = onSleep,
        )
        HubCard(
            icon = {
                RailIcon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = null,
                    size = m.hubIcon,
                    tint = LocalMassRailPalette.current.accent,
                )
            },
            title = stringResource(R.string.mass_api_rail_more),
            subtitle = stringResource(R.string.mass_api_rail_more_subtitle),
            onClick = onMore,
        )
    }
}

@Composable
private fun HubCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    /** Hub feature titles stay Bold; peer/device names can dial down. */
    titleFontWeight: FontWeight = FontWeight.Bold,
) {
    val m = LocalMassRailMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.cornerLg))
            .border(1.dp, RailInk.copy(alpha = 0.1f), RoundedCornerShape(m.cornerLg))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .padding(start = m.padH, end = m.padH, top = m.rowPadV, bottom = m.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(m.hubIconBox)
                .clip(RoundedCornerShape(m.cornerMd))
                .background(LocalMassRailPalette.current.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Column(modifier = Modifier.weight(1f).padding(horizontal = m.padH)) {
            Text(title, color = RailInk, fontSize = m.hubTitleSp, fontWeight = titleFontWeight)
            // Single line only — long locales shrink instead of wrapping to two rows.
            RailAutoFitText(
                text = subtitle,
                color = RailMuted,
                maxFontSize = m.secondarySp,
                minFontSize = m.secondarySp * 0.65f,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.padding(top = (3f * m.scale).dp),
            )
        }
        Text(
            text = "›",
            color = RailInk.copy(alpha = 0.45f),
            fontSize = m.chevronSp,
            fontWeight = FontWeight.Light,
        )
    }
}

/**
 * Shrinks [maxFontSize]→[minFontSize] so [text] fits in [maxLines] for the available
 * width. At the floor, remaining overflow uses ellipsis (desc) / clip-safe ellipsis.
 */
@Composable
private fun RailAutoFitText(
    text: String,
    color: Color,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    fontWeight: FontWeight,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val fitted = remember(text, maxWidthPx, maxFontSize, minFontSize, maxLines, fontWeight) {
            val hi = maxFontSize.value
            val lo = min(minFontSize.value, hi)
            if (maxWidthPx <= 0 || text.isEmpty()) {
                hi.sp
            } else {
                fun fits(sizeSp: Float): Boolean {
                    val result = measurer.measure(
                        text = text,
                        style = TextStyle(
                            fontSize = sizeSp.sp,
                            fontWeight = fontWeight,
                        ),
                        overflow = TextOverflow.Clip,
                        softWrap = maxLines > 1,
                        maxLines = maxLines,
                        constraints = Constraints(maxWidth = maxWidthPx),
                    )
                    return !result.hasVisualOverflow
                }
                if (fits(hi)) {
                    hi.sp
                } else if (!fits(lo)) {
                    lo.sp
                } else {
                    var low = lo
                    var high = hi
                    var best = lo
                    repeat(10) {
                        val mid = (low + high) / 2f
                        if (fits(mid)) {
                            best = mid
                            low = mid
                        } else {
                            high = mid
                        }
                    }
                    best.sp
                }
            }
        }
        Text(
            text = text,
            color = color,
            fontSize = fitted,
            fontWeight = fontWeight,
            maxLines = maxLines,
            softWrap = maxLines > 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RailSubBar(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val m = LocalMassRailMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Whole bar backs out (title text / empty space too) — not only the
            // arrow hit box. Trailing chips consume their own taps first. No
            // press indication: a wash/ripple here reads as screen flicker.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            )
            .padding(horizontal = m.padH)
            .padding(bottom = m.padV)
            .border(width = 0.dp, color = Color.Transparent)
            .padding(top = m.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(m.backHit)
                .clip(RoundedCornerShape(m.cornerMd))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            RailIcon(
                imageVector = Icons.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.mass_api_rail_back),
                size = m.backIcon,
                tint = RailInk,
            )
        }
        Text(
            text = title,
            color = RailInk,
            fontSize = m.subBarTitleSp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = m.gapSm),
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(RailLine),
    )
}

@Composable
private fun RailAudioPage(
    players: List<MassPlayer>,
    activePlayerId: String,
    fallbackTrack: String = "",
    onBack: () -> Unit,
    onSaveSync: (Set<String>) -> Unit,
    onSwitchTo: (String) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    var transferMode by remember { mutableStateOf(false) }
    var draftSwitchId by remember(activePlayerId) { mutableStateOf(activePlayerId) }
    val self = players.firstOrNull { it.playerId == activePlayerId }
    val syncPlaying = rememberSyncPlayingPeers()
    // Bidirectional: being paired *by* another device must tick its box too, so
    // the group reads the same from either seat.
    val initialSync = remember(players, activePlayerId, syncPlaying) {
        val leader = players.firstOrNull { it.playerId == activePlayerId }
        players
            .filter { isInSameSyncGroup(it, activePlayerId, leader, syncPlaying) }
            .map { it.playerId }
            .toSet()
    }
    var draftSync by remember(initialSync) { mutableStateOf(initialSync) }
    val leaderTrack = self?.trackTitle?.takeIf { it.isNotBlank() }
        ?: fallbackTrack
    val transferLabel = stringResource(R.string.mass_api_rail_transfer)
    val emptyAudio = stringResource(R.string.mass_api_rail_empty_audio)

    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(
            title = stringResource(R.string.mass_api_rail_audio),
            onBack = onBack,
            trailing = {
                // Move playback to another device
                RailChipBtn(
                    label = transferLabel,
                    icon = RailSwapHorizIcon,
                    active = transferMode,
                    onClick = {
                        transferMode = !transferMode
                        draftSwitchId = activePlayerId
                    },
                )
            },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = m.padH, vertical = m.padV),
            verticalArrangement = Arrangement.spacedBy(m.gapMd),
        ) {
            itemsIndexed(players, key = { _, p -> p.playerId }) { _, p ->
                val isSelf = p.playerId == activePlayerId
                if (transferMode) {
                    val selected = draftSwitchId == p.playerId
                    val transferBlocked = isTransferBlockedPeer(
                        p,
                        activePlayerId,
                        self,
                        leaderTrack,
                        syncPlaying,
                    )
                    val rowTrack = when {
                        // Synced peers share the leader track in the UI.
                        transferBlocked && leaderTrack.isNotBlank() -> leaderTrack
                        p.trackTitle.isNotBlank() -> p.trackTitle
                        isSelf -> leaderTrack
                        else -> ""
                    }
                    AudioPlayerRow(
                        player = p,
                        trackLabel = rowTrack.ifBlank { emptyAudio },
                        trackEmpty = rowTrack.isBlank(),
                        selected = selected && !transferBlocked,
                        square = false,
                        checkEnabled = !transferBlocked,
                        dimmed = transferBlocked,
                        onToggle = {
                            if (transferBlocked) return@AudioPlayerRow
                            draftSwitchId = p.playerId
                            if (p.playerId != activePlayerId) {
                                onSwitchTo(p.playerId)
                            }
                        },
                    )
                } else {
                    val on = isSelf || p.playerId in draftSync
                    val track = when {
                        on && leaderTrack.isNotBlank() -> leaderTrack
                        p.trackTitle.isNotBlank() -> p.trackTitle
                        else -> ""
                    }
                    AudioPlayerRow(
                        player = p,
                        trackLabel = track.ifBlank { emptyAudio },
                        trackEmpty = track.isBlank(),
                        selected = on,
                        square = true,
                        checkEnabled = !isSelf,
                        onToggle = {
                            if (isSelf) return@AudioPlayerRow
                            draftSync = if (p.playerId in draftSync) {
                                draftSync - p.playerId
                            } else {
                                draftSync + p.playerId
                            }
                        },
                    )
                }
            }
        }

        if (!transferMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.dp, color = Color.Transparent)
                    .background(Color.Transparent)
                    .padding(horizontal = m.padH, vertical = m.rowPadV),
                horizontalArrangement = Arrangement.End,
            ) {
                FootBtn(stringResource(R.string.mass_api_rail_cancel)) { onBack() }
                Spacer(Modifier.width(m.gapMd))
                FootBtn(stringResource(R.string.mass_api_rail_save), primary = true) {
                    onSaveSync(draftSync)
                }
            }
        }
    }
}

@Composable
private fun RailChipBtn(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) LocalMassRailPalette.current.accent.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.06f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = m.chipPadH, vertical = m.chipPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailIcon(
            imageVector = icon,
            contentDescription = label,
            size = m.chipIcon,
            tint = LocalMassRailPalette.current.accent,
        )
        Spacer(Modifier.width((5f * m.scale).dp))
        Text(
            text = label,
            color = LocalMassRailPalette.current.accent,
            fontSize = m.secondarySp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AudioPlayerRow(
    player: MassPlayer,
    trackLabel: String,
    trackEmpty: Boolean,
    selected: Boolean,
    square: Boolean,
    checkEnabled: Boolean,
    dimmed: Boolean = false,
    onToggle: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val nameColor = when {
        dimmed -> RailFaint
        selected -> LocalMassRailPalette.current.accent
        else -> RailInk
    }
    val trackColor = when {
        dimmed -> RailFaint.copy(alpha = 0.55f)
        trackEmpty -> RailFaint
        else -> RailMuted
    }
    val iconTint = when {
        dimmed -> RailInk.copy(alpha = 0.28f)
        selected -> LocalMassRailPalette.current.accent
        else -> RailInk.copy(alpha = 0.65f)
    }
    val checkBorder = when {
        dimmed -> RailInk.copy(alpha = 0.18f)
        selected -> LocalMassRailPalette.current.accent
        else -> RailInk.copy(alpha = 0.42f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.cornerMd))
            .then(
                if (selected && !dimmed) {
                    Modifier
                        .background(LocalMassRailPalette.current.accent.copy(alpha = 0.12f))
                        .border(1.dp, LocalMassRailPalette.current.accent.copy(alpha = 0.28f), RoundedCornerShape(m.cornerMd))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = checkEnabled, onClick = onToggle)
            .padding(horizontal = m.gapSm, vertical = m.padV)
            .then(if (dimmed) Modifier.alpha(0.55f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 5.dp)
                .size(m.avatarSize)
                .clip(RoundedCornerShape(m.cornerMd))
                .background(
                    if (selected && !dimmed) LocalMassRailPalette.current.accent.copy(alpha = 0.16f)
                    else Color.White.copy(alpha = 0.07f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            RailIcon(
                imageVector = RailSpeakerIcon,
                contentDescription = null,
                size = m.iconLg,
                tint = iconTint,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = m.gapMd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = player.displayName,
                color = nameColor,
                fontSize = m.titleSp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            // Match NP: strip (…)/(…) and marquee long titles; end-pad so dissolve
            // doesn't clip the last glyph. Empty placeholder stays static.
            if (trackEmpty) {
                Text(
                    text = trackLabel,
                    color = trackColor,
                    fontSize = m.captionSp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = (3f * m.scale).dp),
                )
            } else {
                val shown = stripParenthetical(trackLabel).ifBlank { trackLabel }
                CautiousMarqueeText(
                    text = shown,
                    color = trackColor,
                    fontSize = m.captionSp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = (3f * m.scale).dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(m.avatarSize)
                .clickable(enabled = checkEnabled, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(m.checkInner)
                    .clip(if (square) RoundedCornerShape(m.cornerSm) else CircleShape)
                    .border(
                        (1.5f * m.scale).dp,
                        checkBorder,
                        if (square) RoundedCornerShape(m.cornerSm) else CircleShape,
                    )
                    .background(
                        if (selected && !dimmed) LocalMassRailPalette.current.accent
                        else Color.White.copy(alpha = 0.04f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected && !dimmed) {
                    RailIcon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        size = m.checkIcon,
                        tint = LocalMassRailPalette.current.onAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun RailSpeakersPage(
    players: List<MassPlayer>,
    activePlayerId: String,
    onBack: () -> Unit,
    onGroupVolume: (Int) -> Unit,
    onMemberVolume: (String, Int) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val leader = players.firstOrNull { it.playerId == activePlayerId }
    val syncPlaying = rememberSyncPlayingPeers()
    val members = players.filter {
        it.playerId == activePlayerId ||
            isInSameSyncGroup(it, activePlayerId, leader, syncPlaying)
    }
    var groupVol by remember(leader?.groupVolume, leader?.volumeLevel) {
        mutableIntStateOf(leader?.groupVolume ?: leader?.volumeLevel ?: 50)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = stringResource(R.string.mass_api_rail_speakers), onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.padH, end = m.padH, top = m.padV, bottom = m.padV + 10.dp),
        ) {
            item {
                VolBlock(stringResource(R.string.mass_api_rail_group), groupVol) {
                    groupVol = it
                    onGroupVolume(it)
                }
            }
            item {
                // Same inset hairline as More page (between group and member volumes).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = m.gapSm)
                        .height(1.dp)
                        .background(RailLine),
                )
            }
            itemsIndexed(members, key = { _, p -> p.playerId }) { _, p ->
                var vol by remember(p.playerId, p.volumeLevel) { mutableIntStateOf(p.volumeLevel) }
                VolBlock(p.displayName, vol) {
                    vol = it
                    onMemberVolume(p.playerId, it)
                }
            }
        }
    }
}

@Composable
private fun VolBlock(label: String, value: Int, onChange: (Int) -> Unit) {
    val m = LocalMassRailMetrics.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = RailInk, fontSize = m.bodySp, fontWeight = FontWeight.Bold)
            Text("$value%", color = LocalMassRailPalette.current.accent, fontSize = m.bodySp, fontWeight = FontWeight.Bold)
        }
        TickSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = RailInk,
                activeTrackColor = LocalMassRailPalette.current.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
            ),
        )
    }
}

/**
 * Sync delay tuner:
 * - Top: this device — local Sendspin [syncOffsetMs]. Manual ≠ 0 replaces
 *   auto-calibrate (intentional, not stacked).
 * - Below: already-synced peers (either seat) are drawn immediately.
 *   Live VAL → Ava remote slider; otherwise "not supported".
 *   Unpaired MA players never get a row.
 */
private data class RemoteDelayRow(
    val player: MassPlayer,
    val bound: AvaSyncOffsetPeer.BoundPeer?,
    /** First probe round still running — show a spinner, not "not supported". */
    val probing: Boolean = false,
)

/** VAL Ava peers, then probing / unsupported. */
private fun remoteDelayRank(row: RemoteDelayRow): Int = when {
    row.bound != null -> 0
    row.probing -> 1
    else -> 2
}

@Composable
private fun RailSyncDelayPage(
    players: List<MassPlayer>,
    activePlayerId: String,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncStore = remember(context) { SendspinSettingsStore(context.sendspinSettingsStore) }
    val storedOffset by syncStore.syncOffsetMs.collectAsState(initial = 0)
    var localMs by remember(storedOffset) {
        mutableFloatStateOf(storedOffset.coerceIn(-1000, 1000).toFloat())
    }
    val syncPlaying = rememberSyncPlayingPeers()
    val self = players.firstOrNull { it.playerId == activePlayerId }
    val peers = players.filter {
        it.playerId != activePlayerId &&
            isInSameSyncGroup(it, activePlayerId, self, syncPlaying)
    }
    var remoteDelayRows by remember { mutableStateOf<List<RemoteDelayRow>>(emptyList()) }
    val autoCalibrateLabel = stringResource(R.string.mass_api_rail_sync_delay_auto_calibrate)
    val unsupportedHint = stringResource(R.string.mass_api_rail_peer_unsupported)
    val unlockLabel = stringResource(R.string.mass_api_rail_sync_delay_unlock)
    var unlocked by remember { mutableStateOf(false) }

    LaunchedEffect(peers.joinToString(",") { it.playerId }) {
        if (peers.isEmpty()) {
            remoteDelayRows = emptyList()
            return@LaunchedEffect
        }
        // Paint known peers instantly on re-entry; only never-reached ones spin.
        remoteDelayRows = peers.map { p ->
            val cached = AvaSyncOffsetPeer.cachedBoundPeer(p.playerId)
            RemoteDelayRow(p, cached, probing = cached == null)
        }
        // Keep retrying only the rows still unresolved: presence / beacons can
        // arrive after this page opened, and a first-pass miss must not stay
        // "not supported" until the peer set happens to change.
        var firstRound = true
        while (true) {
            // First round also refreshes cached rows so a stale offset is
            // corrected once; later rounds chase only what never answered.
            val pending = remoteDelayRows
                .filter { firstRound || it.bound == null }
                .map { it.player }
            if (pending.isEmpty()) break
            val probed = coroutineScope {
                pending.map { p ->
                    async {
                        p.playerId to probeWithRetry {
                            AvaSyncOffsetPeer.bindForMassPlayer(p.playerId, p.displayName)
                        }
                    }
                }.awaitAll().toMap()
            }
            remoteDelayRows = remoteDelayRows
                .map { row ->
                    // No reply keeps whatever the row already showed, so a
                    // refresh miss never demotes a working peer to a hint row.
                    val found = probed[row.player.playerId]
                    row.copy(
                        bound = found ?: row.bound,
                        probing = false,
                    )
                }
                .sortedBy { remoteDelayRank(it) }
            firstRound = false
            if (remoteDelayRows.none { it.bound == null }) break
            delay(PEER_PROBE_RETRY_MS)
        }
    }

    fun applyLocalOffset(ms: Int) {
        val clamped = ms.coerceIn(-1000, 1000)
        scope.launch {
            syncStore.syncOffsetMs.set(clamped)
        }
        VoiceSatelliteService.getInstance()?.updateSendspinSyncOffset(clamped)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(
            title = stringResource(R.string.mass_api_rail_sync_delay),
            onBack = onBack,
            trailing = {
                RailChipBtn(
                    label = unlockLabel,
                    icon = if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    active = unlocked,
                    onClick = { unlocked = !unlocked },
                )
            },
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.padH, end = m.padH, top = m.padV, bottom = m.padV + 10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.mass_api_rail_sync_delay_this_device),
                    color = RailMuted,
                    fontSize = m.secondarySp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = m.gapSm),
                )
                DelayBlock(
                    label = self?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.mass_api_rail_sync_delay_this_device),
                    valueMs = localMs.toInt(),
                    minMs = -1000,
                    maxMs = 1000,
                    stepMs = 10,
                    enabled = unlocked,
                    onChange = { localMs = it.toFloat() },
                    onChangeFinished = { applyLocalOffset(localMs.toInt()) },
                    onZeroSnap = { onToast(autoCalibrateLabel) },
                )
            }
            if (remoteDelayRows.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mass_api_rail_sync_delay_other_device),
                        color = RailMuted,
                        fontSize = m.secondarySp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = m.gapMd, bottom = m.gapSm),
                    )
                }
                itemsIndexed(remoteDelayRows, key = { _, row -> row.player.playerId }) { index, row ->
                    val bound = row.bound
                    if (index > 0 &&
                        remoteDelayRank(remoteDelayRows[index - 1]) != remoteDelayRank(row)
                    ) {
                        RailHintDivider()
                    }
                    when {
                        bound != null -> RailRemoteDelayRow(
                            player = row.player,
                            bound = bound,
                            enabled = unlocked,
                            onToast = onToast,
                        )
                        row.probing -> RailPeerProbingRow(name = row.player.displayName)
                        else -> RailPeerHintRow(
                            name = row.player.displayName,
                            hint = unsupportedHint,
                            nameFontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Peers beacon every 5s, so a single dropped packet used to drop a healthy Ava
 * before the page ever drew it. Poke, then give the LAN a widening settle window
 * before giving up — the protocol and the peer's own gates are untouched.
 */
private suspend fun <T> probeWithRetry(probe: suspend () -> T?): T? {
    // Probe first: a peer already in the beacon table answers unicast in
    // milliseconds, and settling before the first try only delayed that.
    probe()?.let { return it }
    repeat(PEER_PROBE_ATTEMPTS - 1) { attempt ->
        AvaVoiceDiscovery.pokeLanDiscovery()
        // Widening settle so a peer that beacons late still answers a retry.
        delay(if (attempt == 0) 600L else 1_200L)
        probe()?.let { return it }
    }
    return null
}

@Composable
private fun RailRemoteDelayRow(
    player: MassPlayer,
    bound: AvaSyncOffsetPeer.BoundPeer,
    enabled: Boolean,
    onToast: (String) -> Unit,
) {
    val autoCalibrateLabel = stringResource(R.string.mass_api_rail_sync_delay_auto_calibrate)
    var valueMs by remember(bound.peer.id) { mutableIntStateOf(bound.offsetMs) }

    LaunchedEffect(bound.peer.id, bound.peer.host) {
        @OptIn(FlowPreview::class)
        snapshotFlow { valueMs }
            .drop(1)
            .debounce(250L)
            .collect { ms ->
                AvaSyncOffsetPeer.setOffsetMs(bound.peer, ms)
            }
    }

    DelayBlock(
        label = player.displayName,
        valueMs = valueMs,
        minMs = AvaSyncOffsetPeer.MIN_MS,
        maxMs = AvaSyncOffsetPeer.MAX_MS,
        stepMs = 10,
        enabled = enabled,
        onChange = { valueMs = it },
        onZeroSnap = { onToast(autoCalibrateLabel) },
        labelFontWeight = FontWeight.Normal,
    )
}

/** Peer row while the first probe is still in flight. */
@Composable
private fun RailPeerProbingRow(name: String) {
    val m = LocalMassRailMetrics.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size((14f * m.scale).coerceIn(12f, 18f).dp),
            strokeWidth = (1.6f * m.scale).coerceIn(1.2f, 2f).dp,
            color = LocalMassRailPalette.current.accent,
            trackColor = RailInk.copy(alpha = 0.12f),
        )
        Text(
            text = name,
            color = RailMuted,
            fontSize = m.bodySp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(start = m.gapSm),
        )
    }
}

@Composable
private fun RailPeerHintRow(
    name: String,
    hint: String,
    nameFontWeight: FontWeight = FontWeight.Bold,
) {
    val m = LocalMassRailMetrics.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd)) {
        Text(name, color = RailMuted, fontSize = m.bodySp, fontWeight = nameFontWeight)
        Text(
            text = hint,
            color = RailMuted,
            fontSize = m.secondarySp,
            modifier = Modifier.padding(top = (3f * m.scale).dp),
        )
    }
}

@Composable
private fun RailHintDivider() {
    val m = LocalMassRailMetrics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = m.gapSm),
        contentAlignment = Alignment.Center,
    ) {
        Text("·", color = RailFaint, fontSize = m.bodySp, fontWeight = FontWeight.Bold)
    }
}

/** Same chrome as [VolBlock]; range matches settings Sendspin sync-offset slider. */
@Composable
private fun DelayBlock(
    label: String,
    valueMs: Int,
    minMs: Int,
    maxMs: Int,
    stepMs: Int,
    onChange: (Int) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
    onZeroSnap: (() -> Unit)? = null,
    /** Local row keeps Bold; peer device names can dial down. */
    labelFontWeight: FontWeight = FontWeight.Bold,
    /**
     * Max steps the value may move toward the finger per accepted sample.
     * Lyrics offset uses 1 so a flick cannot jump hundreds of ms at once.
     */
    maxStepsPerChange: Int = Int.MAX_VALUE,
    /**
     * Minimum gap between accepted value changes while dragging.
     * 0 = unrestricted (sync delay / peers). Lyrics offset uses a slow pace.
     */
    minChangeIntervalMs: Long = 0L,
    enabled: Boolean = true,
) {
    val m = LocalMassRailMetrics.current
    val steps = (((maxMs - minMs) / stepMs) - 1).coerceAtLeast(0)
    // Stick at 0 until the finger pulls past ~2.5 steps — feels like a brief pause.
    val magnetHalfMs = (stepMs * 2.5f).coerceAtLeast(20f)
    val showZeroNotch = minMs < 0 && maxMs > 0
    var latchedAtZero by remember { mutableStateOf(valueMs == 0) }
    var lastChangeElapsed by remember { mutableLongStateOf(0L) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = RailInk, fontSize = m.bodySp, fontWeight = labelFontWeight)
            Text(
                text = stringResource(R.string.mass_api_rail_sync_delay_ms, valueMs),
                color = LocalMassRailPalette.current.accent,
                fontSize = m.bodySp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // Tiny center notch at 0ms (bipolar ± ranges only).
            if (showZeroNotch) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width((1.5f * m.scale).dp)
                        .height((10f * m.scale).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.42f)),
                )
            }
            TickSlider(
                value = valueMs.toFloat(),
                enabled = enabled,
                onValueChange = { raw ->
                    val stepped = (
                        kotlin.math.round(raw / stepMs).toInt() * stepMs
                    ).coerceIn(minMs, maxMs)
                    val target = if (kotlin.math.abs(raw) <= magnetHalfMs) 0 else stepped
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (
                        minChangeIntervalMs > 0L &&
                        target != valueMs &&
                        now - lastChangeElapsed < minChangeIntervalMs
                    ) {
                        return@TickSlider
                    }
                    val next = if (
                        maxStepsPerChange == Int.MAX_VALUE ||
                        target == valueMs ||
                        stepMs <= 0
                    ) {
                        target
                    } else {
                        val maxDelta = maxStepsPerChange * stepMs
                        val delta = (target - valueMs).coerceIn(-maxDelta, maxDelta)
                        (valueMs + delta).coerceIn(minMs, maxMs)
                    }
                    if (next == valueMs) return@TickSlider
                    if (next == 0 && !latchedAtZero) {
                        latchedAtZero = true
                        onZeroSnap?.invoke()
                    } else if (next != 0) {
                        latchedAtZero = false
                    }
                    lastChangeElapsed = now
                    onChange(next)
                },
                onValueChangeFinished = { onChangeFinished?.invoke() },
                valueRange = minMs.toFloat()..maxMs.toFloat(),
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = RailInk,
                    activeTrackColor = LocalMassRailPalette.current.accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

/**
 * 5-band music EQ (Sendspin output), same IA as [RailSyncDelayPage]:
 * - Top: this device — sliders persist to Sendspin settings + live runtime.
 * - Below: already-synced peers (either seat) are drawn immediately.
 *   Live EQ VAL with settings master on → entry card.
 *   Master off or no VAL → "not supported". Unpaired MA players never get a row.
 */
private data class RemoteEqRow(
    val player: MassPlayer,
    val bound: AvaSyncOffsetPeer.BoundEqPeer?,
    /** First probe round still running — show a spinner, not "not supported". */
    val probing: Boolean = false,
)

@Composable
private fun RailEqualizerPage(
    players: List<MassPlayer>,
    activePlayerId: String,
    onBack: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { SendspinSettingsStore(context.sendspinSettingsStore) }
    val localGainsFlow = remember(store) { store.getFlow().map { it.toMusicEqGains() } }
    // Seed from the live audio curve so the first frame matches reality —
    // DataStore's FLAT default made every open flash 0dB → saved gains.
    val localGains by localGainsFlow.collectAsState(
        initial = MusicEqRuntime.get(MusicEqSource.SENDSPIN),
    )

    fun persistLocal(next: MusicEqGains) {
        scope.launch {
            store.setMusicEq(next)
            MusicEqRuntime.set(MusicEqSource.SENDSPIN, next)
        }
    }

    val syncPlaying = rememberSyncPlayingPeers()
    val self = players.firstOrNull { it.playerId == activePlayerId }
    val peers = players.filter {
        it.playerId != activePlayerId &&
            isInSameSyncGroup(it, activePlayerId, self, syncPlaying)
    }
    var remoteEqRows by remember { mutableStateOf<List<RemoteEqRow>>(emptyList()) }
    var openRemote by remember {
        mutableStateOf<Pair<MassPlayer, AvaSyncOffsetPeer.BoundEqPeer>?>(null)
    }
    val unsupportedHint = stringResource(R.string.mass_api_rail_peer_unsupported)

    LaunchedEffect(peers.joinToString(",") { it.playerId }) {
        if (peers.isEmpty()) {
            remoteEqRows = emptyList()
            return@LaunchedEffect
        }
        // Paint known peers instantly on re-entry; only never-reached ones spin.
        remoteEqRows = peers.map { p ->
            val cached = AvaSyncOffsetPeer.cachedBoundEqPeer(p.playerId)?.takeIf { it.gains.enabled }
            RemoteEqRow(p, cached, probing = cached == null)
        }
        // Same retry loop as the delay page. A peer whose EQ master switch is
        // off stays unresolved on purpose, and re-probing lets the card appear
        // as soon as that switch is turned on.
        var firstRound = true
        while (true) {
            // First round also refreshes cached rows so a master switch turned
            // off since then is picked up; later rounds chase only the rest.
            val pending = remoteEqRows
                .filter { firstRound || it.bound == null }
                .map { it.player }
            if (pending.isEmpty()) break
            val probed = coroutineScope {
                pending.map { p ->
                    async {
                        p.playerId to probeWithRetry {
                            AvaSyncOffsetPeer.bindEqForMassPlayer(p.playerId, p.displayName)
                        }
                    }
                }.awaitAll().toMap()
            }
            remoteEqRows = remoteEqRows
                .map { row ->
                    val raw = probed[row.player.playerId]
                    when {
                        // A reply is authoritative either way: master on gives a
                        // card, master off must clear a card we drew earlier.
                        raw != null -> row.copy(bound = raw.takeIf { it.gains.enabled }, probing = false)
                        else -> row.copy(probing = false)
                    }
                }
                .sortedBy { it.bound == null }
            firstRound = false
            if (remoteEqRows.none { it.bound == null }) break
            delay(PEER_PROBE_RETRY_MS)
        }
    }

    val open = openRemote
    if (open != null) {
        RailRemoteEqPage(
            playerName = open.first.displayName,
            bound = open.second,
            onBack = { openRemote = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = stringResource(R.string.mass_api_rail_eq), onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.padH, end = m.padH, top = m.padV, bottom = m.padV + 10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.mass_api_rail_sync_delay_this_device),
                    color = RailMuted,
                    fontSize = m.secondarySp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = m.gapSm),
                )
                RailEqBandList(gains = localGains, onCommit = { persistLocal(it) })
            }
            if (remoteEqRows.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mass_api_rail_sync_delay_other_device),
                        color = RailMuted,
                        fontSize = m.secondarySp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = m.gapMd, bottom = m.gapSm),
                    )
                }
                itemsIndexed(remoteEqRows, key = { _, row -> row.player.playerId }) { index, row ->
                    val bound = row.bound
                    if (bound == null && index > 0 && remoteEqRows[index - 1].bound != null) {
                        RailHintDivider()
                    }
                    if (bound != null) {
                        Box(modifier = Modifier.padding(bottom = m.gapMd)) {
                            HubCard(
                                icon = {
                                    RailIcon(
                                        imageVector = RailEqIcon,
                                        contentDescription = null,
                                        size = m.hubIcon,
                                        tint = LocalMassRailPalette.current.accent,
                                    )
                                },
                                title = row.player.displayName,
                                subtitle = stringResource(R.string.mass_api_rail_eq_remote_subtitle),
                                onClick = { openRemote = row.player to bound },
                                titleFontWeight = FontWeight.Normal,
                            )
                        }
                    } else if (row.probing) {
                        RailPeerProbingRow(name = row.player.displayName)
                    } else {
                        RailPeerHintRow(
                            name = row.player.displayName,
                            hint = unsupportedHint,
                            nameFontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** Third-level page: the peer's 5 bands; each release pushes the curve over presence UDP. */
@Composable
private fun RailRemoteEqPage(
    playerName: String,
    bound: AvaSyncOffsetPeer.BoundEqPeer,
    onBack: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val scope = rememberCoroutineScope()
    var gains by remember(bound.peer.id) { mutableStateOf(bound.gains) }
    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = playerName, onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.padH, end = m.padH, top = m.padV, bottom = m.padV + 10.dp),
        ) {
            item {
                RailEqBandList(
                    gains = gains,
                    onCommit = { next ->
                        gains = next
                        scope.launch { AvaSyncOffsetPeer.setEqGains(bound.peer, next) }
                    },
                )
            }
        }
    }
}

@Composable
private fun RailEqBandList(
    gains: MusicEqGains,
    onCommit: (MusicEqGains) -> Unit,
) {
    val labels = listOf(
        stringResource(R.string.settings_music_eq_band_bass),
        stringResource(R.string.settings_music_eq_band_low_mid),
        stringResource(R.string.settings_music_eq_band_mid),
        stringResource(R.string.settings_music_eq_band_upper_mid),
        stringResource(R.string.settings_music_eq_band_treble),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        RailEqPresetBlock(
            gains = gains,
            onSelect = { preset -> onCommit(preset.applyTo(gains)) },
        )
        labels.forEachIndexed { index, label ->
            RailEqBandBlock(
                label = label,
                valueDb = gains.bandDb(index),
                onCommit = { db -> onCommit(gains.withBandDb(index, db)) },
            )
        }
    }
}

/**
 * Preset picker for the rail. Expands in place rather than in a [androidx.compose.ui.window.Popup]:
 * the rail is hosted in a TYPE_APPLICATION_OVERLAY window (see VinylCoverService) that is also
 * FLAG_NOT_FOCUSABLE, so a second Compose window has no usable token to attach to.
 */
@Composable
private fun RailEqPresetBlock(
    gains: MusicEqGains,
    onSelect: (MusicEqPreset) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val density = LocalDensity.current
    val accent = LocalMassRailPalette.current.accent
    var expanded by remember { mutableStateOf(false) }
    val current = MusicEqPreset.matching(gains)
    // Half a row past four: enough to browse, and the clipped row signals there is more below.
    val rowHeight = m.rowPadV * 2 + with(density) { m.bodySp.toDp() } * 1.4f
    val listMaxHeight = rowHeight * 4.5f
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(m.cornerMd))
                .clickable { expanded = !expanded }
                .padding(vertical = m.gapSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_music_eq_preset),
                color = RailInk,
                fontSize = m.bodySp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = current?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.settings_music_eq_preset_custom),
                    color = accent,
                    fontSize = m.bodySp,
                    fontWeight = FontWeight.Bold,
                )
                RailIcon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    size = m.iconSm,
                    tint = RailMuted,
                )
            }
        }
        if (expanded) {
            SettingsEdgeFadeScrollColumn(
                maxHeight = listMaxHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.cornerMd))
                    .background(Color.White.copy(alpha = 0.06f)),
                fadeHeight = rowHeight * 0.6f,
                verticalArrangement = Arrangement.Top,
            ) {
                MusicEqPreset.entries.forEachIndexed { index, preset ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(RailLine),
                        )
                    }
                    val isCurrent = preset == current
                    Text(
                        text = stringResource(preset.labelRes),
                        color = if (isCurrent) accent else RailInk,
                        fontSize = m.bodySp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = false
                                onSelect(preset)
                            }
                            .padding(horizontal = m.rowPadH, vertical = m.rowPadV),
                    )
                }
            }
        }
    }
}

/** Same chrome as [DelayBlock]; range / steps match the settings EQ sliders (±10dB, 0.5). */
@Composable
private fun RailEqBandBlock(
    label: String,
    valueDb: Float,
    onCommit: (Float) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    var slider by remember(valueDb) { mutableFloatStateOf(valueDb) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = RailInk, fontSize = m.bodySp, fontWeight = FontWeight.Bold)
            Text(
                text = String.format(java.util.Locale.US, "%+.1f dB", slider),
                color = LocalMassRailPalette.current.accent,
                fontSize = m.bodySp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // Tiny center notch at 0dB (range is symmetric ±10).
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width((1.5f * m.scale).dp)
                    .height((10f * m.scale).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.42f)),
            )
            TickSlider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onCommit(MusicEqGains.clampDb(slider)) },
                valueRange = MusicEqGains.MIN_DB..MusicEqGains.MAX_DB,
                steps = ((MusicEqGains.MAX_DB - MusicEqGains.MIN_DB).toInt() * 2) - 1,
                colors = SliderDefaults.colors(
                    thumbColor = RailInk,
                    activeTrackColor = LocalMassRailPalette.current.accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

/**
 * Overlay-state lyric tuner — same IA as [RailEqualizerPage]:
 * - Top: this device — offset + follow (overlay SharedPreferences).
 * - Below: already-synced peers as HubCards → third-level remote page.
 *   Live LYRICS VAL → entry card; no VAL → "not supported".
 */
private data class RemoteLyricsRow(
    val player: MassPlayer,
    val bound: AvaSyncOffsetPeer.BoundLyricsPeer?,
    /** First probe round still running — show a spinner, not "not supported". */
    val probing: Boolean = false,
)

@Composable
private fun RailLyricsPage(
    players: List<MassPlayer>,
    activePlayerId: String,
    onBack: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val context = LocalContext.current
    LyricDisplayRuntime.ensureLoaded(context)
    val leadMs by LyricDisplayRuntime.leadMs.collectAsState(
        initial = LyricDisplayRuntime.currentLeadMs(),
    )
    val followStep by LyricDisplayRuntime.followStep.collectAsState(
        initial = LyricDisplayRuntime.currentFollowStep(),
    )

    val syncPlaying = rememberSyncPlayingPeers()
    val self = players.firstOrNull { it.playerId == activePlayerId }
    val peers = players.filter {
        it.playerId != activePlayerId &&
            isInSameSyncGroup(it, activePlayerId, self, syncPlaying)
    }
    var remoteLyricsRows by remember { mutableStateOf<List<RemoteLyricsRow>>(emptyList()) }
    var openRemote by remember {
        mutableStateOf<Pair<MassPlayer, AvaSyncOffsetPeer.BoundLyricsPeer>?>(null)
    }
    val unsupportedHint = stringResource(R.string.mass_api_rail_peer_unsupported)

    LaunchedEffect(peers.joinToString(",") { it.playerId }) {
        if (peers.isEmpty()) {
            remoteLyricsRows = emptyList()
            return@LaunchedEffect
        }
        remoteLyricsRows = peers.map { p ->
            val cached = AvaSyncOffsetPeer.cachedBoundLyricsPeer(p.playerId)
            RemoteLyricsRow(p, cached, probing = cached == null)
        }
        var firstRound = true
        while (true) {
            val pending = remoteLyricsRows
                .filter { firstRound || it.bound == null }
                .map { it.player }
            if (pending.isEmpty()) break
            val probed = coroutineScope {
                pending.map { p ->
                    async {
                        p.playerId to probeWithRetry {
                            AvaSyncOffsetPeer.bindLyricsForMassPlayer(p.playerId, p.displayName)
                        }
                    }
                }.awaitAll().toMap()
            }
            remoteLyricsRows = remoteLyricsRows
                .map { row ->
                    val found = probed[row.player.playerId]
                    row.copy(bound = found ?: row.bound, probing = false)
                }
                .sortedBy { it.bound == null }
            firstRound = false
            if (remoteLyricsRows.none { it.bound == null }) break
            delay(PEER_PROBE_RETRY_MS)
        }
    }

    val open = openRemote
    if (open != null) {
        RailRemoteLyricsPage(
            playerName = open.first.displayName,
            bound = open.second,
            onBack = { openRemote = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = stringResource(R.string.mass_api_rail_lyrics), onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.padH, end = m.padH, top = m.padV, bottom = m.padV + 10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.mass_api_rail_sync_delay_this_device),
                    color = RailMuted,
                    fontSize = m.secondarySp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = m.gapSm),
                )
                DelayBlock(
                    label = stringResource(R.string.mass_api_rail_lyrics_offset),
                    valueMs = leadMs.toInt(),
                    minMs = LyricDisplayRuntime.OFFSET_MIN_MS.toInt(),
                    maxMs = LyricDisplayRuntime.OFFSET_MAX_MS.toInt(),
                    stepMs = LyricDisplayRuntime.OFFSET_STEP_MS.toInt(),
                    maxStepsPerChange = 1,
                    minChangeIntervalMs = 110L,
                    onChange = { LyricDisplayRuntime.setLeadMs(context, it.toLong()) },
                )
                FollowBlock(
                    step = followStep,
                    onChange = { LyricDisplayRuntime.setFollowStep(context, it) },
                )
            }
            if (remoteLyricsRows.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mass_api_rail_sync_delay_other_device),
                        color = RailMuted,
                        fontSize = m.secondarySp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = m.gapMd, bottom = m.gapSm),
                    )
                }
                itemsIndexed(remoteLyricsRows, key = { _, row -> row.player.playerId }) { index, row ->
                    val bound = row.bound
                    if (bound == null && index > 0 && remoteLyricsRows[index - 1].bound != null) {
                        RailHintDivider()
                    }
                    if (bound != null) {
                        Box(modifier = Modifier.padding(bottom = m.gapMd)) {
                            HubCard(
                                icon = {
                                    RailIcon(
                                        imageVector = RailLyricsIcon,
                                        contentDescription = null,
                                        size = m.hubIcon,
                                        tint = LocalMassRailPalette.current.accent,
                                    )
                                },
                                title = row.player.displayName,
                                subtitle = stringResource(R.string.mass_api_rail_lyrics_remote_subtitle),
                                onClick = { openRemote = row.player to bound },
                                titleFontWeight = FontWeight.Normal,
                            )
                        }
                    } else if (row.probing) {
                        RailPeerProbingRow(name = row.player.displayName)
                    } else {
                        RailPeerHintRow(
                            name = row.player.displayName,
                            hint = unsupportedHint,
                            nameFontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** Third-level page: peer lyric offset + follow; each change pushes over presence UDP. */
@Composable
private fun RailRemoteLyricsPage(
    playerName: String,
    bound: AvaSyncOffsetPeer.BoundLyricsPeer,
    onBack: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    var leadMs by remember(bound.peer.id) {
        mutableIntStateOf(bound.state.leadMs.toInt())
    }
    var followStep by remember(bound.peer.id) {
        mutableIntStateOf(bound.state.followStep)
    }

    LaunchedEffect(bound.peer.id, bound.peer.host) {
        @OptIn(FlowPreview::class)
        snapshotFlow { leadMs to followStep }
            .drop(1)
            .debounce(250L)
            .collect { (ms, step) ->
                AvaSyncOffsetPeer.setLyricsState(
                    bound.peer,
                    AvaSyncOffsetPeer.LyricPeerState(
                        leadMs = ms.toLong(),
                        followStep = step,
                    ),
                )
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = playerName, onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(start = m.padH, end = m.padH, top = m.padV, bottom = m.padV + 10.dp),
            verticalArrangement = Arrangement.spacedBy(m.gapMd),
        ) {
            item {
                DelayBlock(
                    label = stringResource(R.string.mass_api_rail_lyrics_offset),
                    valueMs = leadMs,
                    minMs = LyricDisplayRuntime.OFFSET_MIN_MS.toInt(),
                    maxMs = LyricDisplayRuntime.OFFSET_MAX_MS.toInt(),
                    stepMs = LyricDisplayRuntime.OFFSET_STEP_MS.toInt(),
                    maxStepsPerChange = 1,
                    minChangeIntervalMs = 110L,
                    onChange = { leadMs = it },
                )
            }
            item {
                FollowBlock(
                    step = followStep,
                    onChange = { followStep = it },
                )
            }
        }
    }
}

@Composable
private fun FollowBlock(
    step: Int,
    onChange: (Int) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val names = listOf(
        stringResource(R.string.mass_api_rail_lyrics_follow_steady),
        stringResource(R.string.mass_api_rail_lyrics_follow_calmer),
        stringResource(R.string.mass_api_rail_lyrics_follow_default),
        stringResource(R.string.mass_api_rail_lyrics_follow_closer),
        stringResource(R.string.mass_api_rail_lyrics_follow_tight),
    )
    val defaultStep = LyricDisplayRuntime.FOLLOW_DEFAULT
    val coerced = step.coerceIn(LyricDisplayRuntime.FOLLOW_MIN, LyricDisplayRuntime.FOLLOW_MAX)
    val magnetHalf = 0.35f
    var latchedAtDefault by remember { mutableStateOf(coerced == defaultStep) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = m.gapMd)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.mass_api_rail_lyrics_follow),
                color = RailInk,
                fontSize = m.bodySp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = names[coerced],
                color = LocalMassRailPalette.current.accent,
                fontSize = m.bodySp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width((1.5f * m.scale).dp)
                    .height((10f * m.scale).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.42f)),
            )
            TickSlider(
                value = coerced.toFloat(),
                onValueChange = { raw ->
                    val stepped = kotlin.math.round(raw).toInt()
                        .coerceIn(LyricDisplayRuntime.FOLLOW_MIN, LyricDisplayRuntime.FOLLOW_MAX)
                    val snapped = if (kotlin.math.abs(raw - defaultStep) <= magnetHalf) {
                        defaultStep
                    } else {
                        stepped
                    }
                    if (snapped == defaultStep && !latchedAtDefault) {
                        latchedAtDefault = true
                    } else if (snapped != defaultStep) {
                        latchedAtDefault = false
                    }
                    onChange(snapped)
                },
                valueRange = LyricDisplayRuntime.FOLLOW_MIN.toFloat()..
                    LyricDisplayRuntime.FOLLOW_MAX.toFloat(),
                steps = LyricDisplayRuntime.FOLLOW_MAX - LyricDisplayRuntime.FOLLOW_MIN - 1,
                colors = SliderDefaults.colors(
                    thumbColor = RailInk,
                    activeTrackColor = LocalMassRailPalette.current.accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                names[LyricDisplayRuntime.FOLLOW_MIN],
                color = Color.White.copy(alpha = 0.45f),
                fontSize = m.captionSp,
            )
            Text(
                names[LyricDisplayRuntime.FOLLOW_MAX],
                color = Color.White.copy(alpha = 0.45f),
                fontSize = m.captionSp,
            )
        }
    }
}

@Composable
private fun RailSleepPage(
    sleepMinutes: Int,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val presets = listOf(15, 30, 45, 60, 90, 120)
    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = stringResource(R.string.mass_api_rail_sleep), onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = m.padH, vertical = m.rowPadV),
        ) {
            presets.chunked(3).forEach { row ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = m.gapMd),
                        horizontalArrangement = Arrangement.spacedBy(m.gapMd),
                    ) {
                        row.forEach { mins ->
                            val on = sleepMinutes == mins
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(m.cornerMd))
                                    .border(
                                        1.dp,
                                        if (on) LocalMassRailPalette.current.accent.copy(alpha = 0.45f) else RailLine,
                                        RoundedCornerShape(m.cornerMd),
                                    )
                                    .background(
                                        if (on) LocalMassRailPalette.current.accent.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.04f),
                                    )
                                    .clickable { onPick(mins) }
                                    .padding(vertical = m.rowPadV),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.mass_api_rail_sleep_min, mins),
                                    color = if (on) LocalMassRailPalette.current.accent else RailInk,
                                    fontSize = m.bodySp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (sleepMinutes > 0) {
                item {
                    Text(
                        text = stringResource(R.string.mass_api_rail_off),
                        color = Color(0xFFE8A090),
                        fontSize = m.bodySp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(0) }
                            .padding(m.padH),
                    )
                }
            }
        }
    }
}

@Composable
private fun RailMorePage(
    searchAddToQueue: Boolean,
    autoplayEnabled: Boolean,
    crossfadeEnabled: Boolean,
    syncFabExpand: Boolean,
    onBack: () -> Unit,
    onSearchAddToQueue: (Boolean) -> Unit,
    onAutoplay: (Boolean) -> Unit,
    onCrossfade: (Boolean) -> Unit,
    onSyncFabExpand: (Boolean) -> Unit,
    onClearQueue: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    Column(modifier = Modifier.fillMaxSize()) {
        RailSubBar(title = stringResource(R.string.mass_api_rail_more), onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = m.padH, end = m.padH, top = m.gapSm, bottom = m.gapSm + 10.dp),
            verticalArrangement = Arrangement.spacedBy(m.gapMd),
        ) {
            item {
                RailToggleRow(
                    title = stringResource(R.string.mass_api_rail_search_add_to_queue),
                    subtitle = stringResource(R.string.mass_api_rail_search_add_to_queue_subtitle),
                    checked = searchAddToQueue,
                    onCheckedChange = onSearchAddToQueue,
                )
            }
            item {
                RailToggleRow(
                    title = stringResource(R.string.mass_api_rail_autoplay),
                    subtitle = stringResource(R.string.mass_api_rail_autoplay_subtitle),
                    checked = autoplayEnabled,
                    onCheckedChange = onAutoplay,
                )
            }
            item {
                RailToggleRow(
                    title = stringResource(R.string.mass_api_rail_crossfade),
                    subtitle = stringResource(R.string.mass_api_rail_crossfade_subtitle),
                    checked = crossfadeEnabled,
                    onCheckedChange = onCrossfade,
                )
            }
            item {
                RailToggleRow(
                    title = stringResource(R.string.mass_api_rail_sync_fab),
                    subtitle = stringResource(R.string.mass_api_rail_sync_fab_subtitle),
                    checked = syncFabExpand,
                    onCheckedChange = onSyncFabExpand,
                )
            }
            item {
                // Inset hairline (~10dp each side of the content column) then clear action.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = m.gapSm)
                        .height(1.dp)
                        .background(RailLine),
                )
            }
            item {
                RailActionRow(
                    title = stringResource(R.string.mass_api_rail_clear_queue),
                    onClick = onClearQueue,
                )
            }
        }
    }
}

@Composable
private fun RailActionRow(
    title: String,
    onClick: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.cornerLg))
            .border(1.dp, RailInk.copy(alpha = 0.1f), RoundedCornerShape(m.cornerLg))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .padding(start = m.padH, end = m.rowPadH, top = m.rowPadV, bottom = m.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = RailInk,
            fontSize = m.hubTitleSp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RailToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val palette = LocalMassRailPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.cornerLg))
            .border(1.dp, RailInk.copy(alpha = 0.1f), RoundedCornerShape(m.cornerLg))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable { onCheckedChange(!checked) }
            .padding(start = m.padH, end = m.rowPadH, top = m.rowPadV, bottom = m.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = m.padH)) {
            Text(title, color = RailInk, fontSize = m.hubTitleSp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = RailMuted,
                fontSize = m.secondarySp,
                modifier = Modifier.padding(top = (3f * m.scale).dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = RailInk.copy(alpha = 0.85f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                uncheckedBorderColor = RailLine,
            ),
        )
    }
}

@Composable
private fun FootBtn(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val m = LocalMassRailMetrics.current
    val palette = LocalMassRailPalette.current
    Text(
        text = label,
        color = when {
            !enabled -> RailInk.copy(alpha = 0.35f)
            primary -> palette.onAccent
            else -> RailInk
        },
        fontSize = m.bodySp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (primary) palette.accent else Color.White.copy(alpha = 0.06f))
            .border(
                1.dp,
                if (primary) palette.accent else RailLine,
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = m.footPadH, vertical = m.footPadV),
    )
}

@Composable
private fun RailEmpty(msg: String) {
    val m = LocalMassRailMetrics.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = RailFaint, fontSize = m.bodySp)
    }
}

/**
 * Edge tab to open the Mass rail (design `.push-tab`).
 *
 * Visual auto-hides like [SystemStyleEdgeHandle] (present → idle → fade).
 * Default hit strip is full-height × [MassRailMetrics.pushHitW] so an invisible
 * tab stays reachable (landscape). When [handleOnlyClick] is true (portrait),
 * only the **visible** pill opens the rail — faded handle wakes on press but
 * does not open; the tall edge never steals lyric scrolls.
 * [externalRevealTick] lets the parent wake the pill on any overlay press.
 */
@Composable
fun MassApiRailPushTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Parent bumps on overlay press — fade the pill in without opening the rail. */
    externalRevealTick: Long = 0L,
    /**
     * Portrait: open only via the visible handle. Full-height edge clickable
     * fights the lyric wall / NP chrome.
     */
    handleOnlyClick: Boolean = false,
) {
    val m = rememberMassRailMetrics()
    val palette = rememberMassRailPalette()
    val visualAlpha = remember { Animatable(0f) }
    var localRevealTick by remember { mutableLongStateOf(0L) }
    val reveal = { localRevealTick += 1L }
    val onClickState = rememberUpdatedState(onClick)

    // First paint: brief present, then the idle auto-hide cycle.
    LaunchedEffect(Unit) {
        reveal()
    }

    LaunchedEffect(localRevealTick, externalRevealTick) {
        if (localRevealTick <= 0L && externalRevealTick <= 0L) return@LaunchedEffect
        visualAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = SystemStyleEdgeHandleSpec.PRESENT_MS.toInt(),
                easing = FastOutSlowInEasing,
            ),
        )
        delay(SystemStyleEdgeHandleSpec.AUTO_HIDE_MS)
        visualAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = SystemStyleEdgeHandleSpec.HIDE_MS.toInt(),
                easing = FastOutSlowInEasing,
            ),
        )
    }

    // Read in composition so Animatable frames invalidate the pill alpha.
    val drawAlpha = visualAlpha.value
    // Handle-only: ignore opens while the pill is faded out (graphicsLayer
    // alpha=0 still hits). Landscape keeps the invisible full-height strip.
    val handleVisiblyPresent = drawAlpha > 0.2f

    val openOnClick = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {
            reveal()
            onClickState.value()
        },
    )
    val wakeOnPress = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) {
                    reveal()
                }
            }
        }
    }

    CompositionLocalProvider(LocalMassRailPalette provides palette) {
        if (handleOnlyClick) {
            // Exact pill bounds — no taller/wider invisible hot zone.
            Box(
                modifier = modifier
                    .width(m.pushVisualW)
                    .height(m.pushVisualH)
                    .graphicsLayer { alpha = drawAlpha }
                    .clip(RoundedCornerShape(topStart = m.pushCorner, bottomStart = m.pushCorner))
                    .background(RailWell.copy(alpha = 0.88f))
                    .border(
                        width = 1.dp,
                        color = palette.accent.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(topStart = m.pushCorner, bottomStart = m.pushCorner),
                    )
                    .then(wakeOnPress)
                    .then(if (handleVisiblyPresent) openOnClick else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                RailIcon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.mass_api_rail_open),
                    size = m.pushIcon,
                    tint = palette.accent,
                )
            }
        } else {
            // Landscape: full-height edge strip; pill stays centered.
            Box(
                modifier = modifier
                    .width(m.pushHitW)
                    .fillMaxHeight()
                    .then(wakeOnPress)
                    .then(openOnClick),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(m.pushVisualW)
                        .height(m.pushVisualH)
                        .graphicsLayer { alpha = drawAlpha }
                        .clip(RoundedCornerShape(topStart = m.pushCorner, bottomStart = m.pushCorner))
                        .background(RailWell.copy(alpha = 0.88f))
                        .border(
                            width = 1.dp,
                            color = palette.accent.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(topStart = m.pushCorner, bottomStart = m.pushCorner),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    RailIcon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.mass_api_rail_open),
                        size = m.pushIcon,
                        tint = palette.accent,
                    )
                }
            }
        }
    }
}
