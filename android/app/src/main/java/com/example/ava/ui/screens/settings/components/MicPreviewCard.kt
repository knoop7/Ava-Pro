package com.example.ava.ui.screens.settings.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.ava.R
import com.example.ava.esphome.voicesatellite.VoiceSatelliteAudioInput
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.screens.settings.SimpleCard
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.utils.PcmWavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class MicPreviewPhase { Idle, Recording, Saving, Ready }

private enum class MicPreviewSlot { Processed, Raw }

/**
 * Record a short clip of the microphone signal and play it back locally, so the user can hear what
 * the current setup does to their voice.
 *
 * With [withEchoTest] the card also plays a test hum through the software AEC reference path while
 * recording, and keeps the pre-AEC signal of the same pass, so the two clips can be compared.
 */
@Composable
fun MicPreviewCard(
    enabled: Boolean,
    title: String,
    description: String,
    withEchoTest: Boolean = false,
) {
    val context = LocalContext.current
    val accentColor = getAccentColor()
    val titleColor = getTitleColor()
    val neutralColor = getSliderInactiveColor()

    var phase by remember { mutableStateOf(MicPreviewPhase.Idle) }
    var playingSlot by remember { mutableStateOf<MicPreviewSlot?>(null) }
    var preparedSlot by remember { mutableStateOf<MicPreviewSlot?>(null) }
    var elapsedMs by remember { mutableIntStateOf(0) }
    var clipMs by remember { mutableIntStateOf(0) }
    var hasRawClip by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val processedFile = remember { File(context.cacheDir, PROCESSED_CLIP_FILE) }
    val rawFile = remember { File(context.cacheDir, RAW_CLIP_FILE) }
    val player = remember {
        // Plain ExoPlayer on purpose: the satellite players tee playback into the software AEC
        // reference bus, and previewing a clip must not change what echo cancellation adapts to.
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                false,
            )
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playingSlot = null
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            VoiceSatelliteService.getInstance()?.let { service ->
                service.stopEchoTestTone()
                service.stopMicPreviewCapture()
            }
            processedFile.delete()
            rawFile.delete()
        }
    }

    LaunchedEffect(phase) {
        when (phase) {
            MicPreviewPhase.Recording -> {
                while (elapsedMs < VoiceSatelliteAudioInput.MIC_PREVIEW_MAX_MS) {
                    delay(PREVIEW_TICK_MS.toLong())
                    elapsedMs += PREVIEW_TICK_MS
                }
                phase = MicPreviewPhase.Saving
            }

            MicPreviewPhase.Saving -> {
                val service = VoiceSatelliteService.getInstance()
                service?.stopEchoTestTone()
                val clip = service?.stopMicPreviewCapture()
                if (clip == null) {
                    failed = true
                    phase = MicPreviewPhase.Idle
                    return@LaunchedEffect
                }
                val saved = withContext(Dispatchers.IO) {
                    val processedSaved = PcmWavFile.write(
                        processedFile,
                        clip.processed,
                        VoiceSatelliteAudioInput.MIC_PREVIEW_SAMPLE_RATE,
                    )
                    val raw = clip.raw
                    val rawSaved = raw != null && PcmWavFile.write(
                        rawFile,
                        raw,
                        VoiceSatelliteAudioInput.MIC_PREVIEW_SAMPLE_RATE,
                    )
                    processedSaved to rawSaved
                }
                if (!saved.first) {
                    failed = true
                    phase = MicPreviewPhase.Idle
                    return@LaunchedEffect
                }
                hasRawClip = saved.second
                clipMs = clip.processed.size * 1000 / VoiceSatelliteAudioInput.MIC_PREVIEW_SAMPLE_RATE
                preparedSlot = null
                phase = MicPreviewPhase.Ready
            }

            else -> Unit
        }
    }

    val isRecording = phase == MicPreviewPhase.Recording || phase == MicPreviewPhase.Saving
    val hasClip = phase == MicPreviewPhase.Ready

    fun playSlot(slot: MicPreviewSlot) {
        if (playingSlot == slot) {
            player.pause()
            playingSlot = null
            return
        }
        if (preparedSlot != slot) {
            val file = if (slot == MicPreviewSlot.Raw) rawFile else processedFile
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            preparedSlot = slot
        } else if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        player.play()
        playingSlot = slot
    }

    SimpleCard {
        Text(
            text = title,
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) titleColor else getSettingsDescriptionColor(),
        )
        CollapsibleDescriptionText(
            text = description,
            modifier = Modifier.padding(top = 4.dp),
            color = getSettingsDescriptionColor(),
        )

        MicPreviewButton(
            text = when {
                isRecording -> stringResource(R.string.settings_voice_mic_preview_stop)
                withEchoTest -> stringResource(R.string.settings_voice_mic_preview_start_test)
                else -> stringResource(R.string.settings_voice_mic_preview_record)
            },
            textColor = Color.White,
            background = accentColor,
            enabled = enabled && phase != MicPreviewPhase.Saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            onClick = {
                if (isRecording) {
                    phase = MicPreviewPhase.Saving
                    return@MicPreviewButton
                }
                val service = VoiceSatelliteService.getInstance()
                player.pause()
                playingSlot = null
                failed = false
                elapsedMs = 0
                clipMs = 0
                hasRawClip = false
                val toneStarted = !withEchoTest || service?.startEchoTestTone() == true
                if (toneStarted && service?.startMicPreviewCapture(includeRaw = withEchoTest) == true) {
                    phase = MicPreviewPhase.Recording
                } else {
                    service?.stopEchoTestTone()
                    failed = true
                    phase = MicPreviewPhase.Idle
                }
            },
        )

        if (withEchoTest) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MicPreviewButton(
                    text = stringResource(R.string.settings_voice_mic_preview_play_raw),
                    textColor = if (playingSlot == MicPreviewSlot.Raw) Color.White else titleColor,
                    background = if (playingSlot == MicPreviewSlot.Raw) accentColor else neutralColor,
                    enabled = enabled && hasClip && hasRawClip,
                    modifier = Modifier.weight(1f),
                    onClick = { playSlot(MicPreviewSlot.Raw) },
                )
                MicPreviewButton(
                    text = stringResource(R.string.settings_voice_mic_preview_play_processed),
                    textColor = if (playingSlot == MicPreviewSlot.Processed) Color.White else titleColor,
                    background = if (playingSlot == MicPreviewSlot.Processed) accentColor else neutralColor,
                    enabled = enabled && hasClip,
                    modifier = Modifier.weight(1f),
                    onClick = { playSlot(MicPreviewSlot.Processed) },
                )
            }
        } else {
            MicPreviewButton(
                text = if (playingSlot == MicPreviewSlot.Processed) {
                    stringResource(R.string.settings_voice_mic_preview_pause)
                } else {
                    stringResource(R.string.settings_voice_mic_preview_play)
                },
                textColor = titleColor,
                background = neutralColor,
                enabled = enabled && hasClip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                onClick = { playSlot(MicPreviewSlot.Processed) },
            )
        }

        val statusText = when {
            failed -> stringResource(R.string.settings_voice_mic_preview_failed)
            isRecording -> stringResource(
                R.string.settings_voice_mic_preview_recording,
                elapsedMs / 1000,
                VoiceSatelliteAudioInput.MIC_PREVIEW_MAX_MS / 1000,
            )
            hasClip -> stringResource(R.string.settings_voice_mic_preview_recorded, clipMs / 1000)
            else -> ""
        }
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                fontSize = settingsBodyTextSize(),
                color = if (failed) Color(0xFFEF4444) else getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun MicPreviewButton(
    text: String,
    textColor: Color,
    background: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f),
        shape = shape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

private const val PROCESSED_CLIP_FILE = "mic_preview_processed.wav"
private const val RAW_CLIP_FILE = "mic_preview_raw.wav"
private const val PREVIEW_TICK_MS = 200
