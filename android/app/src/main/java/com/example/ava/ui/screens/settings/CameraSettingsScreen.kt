package com.example.ava.ui.screens.settings

import com.example.ava.ui.AvaToast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.mods.ModCameraStreamBridge
import com.example.ava.settings.CameraMode
import com.example.ava.ui.screens.settings.components.*
import kotlinx.coroutines.launch

@Composable
fun CameraSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)

    val hasCamera = viewModel.hasCamera()
    val hasBackCamera = viewModel.hasBackCamera()
    val hasFrontCamera = viewModel.hasFrontCamera()
    val noCameraText = stringResource(R.string.settings_no_camera)

    var cameraStreamModActive by remember {
        mutableStateOf(ModCameraStreamBridge.isActive(context))
    }
    var cameraOwnerName by remember {
        mutableStateOf(ModCameraStreamBridge.activeOwnerName(context))
    }
    val modOwnedHint =
        stringResource(R.string.settings_camera_mod_owned_hint, cameraOwnerName ?: "")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraStreamModActive = ModCameraStreamBridge.isActive(context)
                cameraOwnerName = ModCameraStreamBridge.activeOwnerName(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(cameraStreamModActive) {
        if (cameraStreamModActive) {
            ModCameraStreamBridge.applyHostPolicySuspend(context)
        }
    }

    val cameraEnabled =
        !cameraStreamModActive && experimentalState?.cameraEnabled == true && hasCamera
    val currentMode = try {
        CameraMode.valueOf(experimentalState?.cameraMode ?: "SNAPSHOT")
    } catch (e: Exception) {
        CameraMode.SNAPSHOT
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_camera_enabled)
    ) {
        item(key = "camera_enabled") {
            SettingsDisabledOverlay(
                disabled = cameraStreamModActive,
                hint = modOwnedHint,
            ) {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_camera_enabled),
                        subLabel = if (hasCamera) {
                            stringResource(R.string.settings_camera_enabled_desc)
                        } else {
                            noCameraText
                        }
                    ) {
                        ModernSwitch(
                            checked = if (hasCamera && !cameraStreamModActive) {
                                experimentalState?.cameraEnabled ?: false
                            } else {
                                false
                            },
                            enabled = hasCamera && interactionsEnabled,
                            onCheckedChange = {
                                if (!interactionsEnabled) return@ModernSwitch
                                if (hasCamera) {
                                    coroutineScope.launch {
                                        viewModel.saveCameraEnabled(it)
                                    }
                                } else {
                                    AvaToast.show(context, noCameraText)
                                }
                            }
                        )
                    }

                    if (cameraEnabled) {
                        SettingsDivider()

                        val modeOptions = listOf(CameraMode.SNAPSHOT, CameraMode.VIDEO)
                        val snapshotModeLabel = stringResource(R.string.settings_camera_mode_snapshot)
                        val videoModeLabel = stringResource(R.string.settings_camera_mode_video)

                        SelectSetting(
                            name = stringResource(R.string.settings_camera_mode),
                            selected = currentMode,
                            items = modeOptions,
                            enabled = interactionsEnabled,
                            key = { it.name },
                            value = {
                                when (it) {
                                    CameraMode.SNAPSHOT -> snapshotModeLabel
                                    CameraMode.VIDEO -> videoModeLabel
                                    null -> ""
                                }
                            },
                            onConfirmRequest = {
                                if (it != null && interactionsEnabled) {
                                    coroutineScope.launch {
                                        viewModel.saveCameraMode(it)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        if (cameraEnabled) {
            item(key = "camera_options") {
                SettingsDisabledOverlay(
                    disabled = cameraStreamModActive,
                    hint = modOwnedHint,
                ) {
                    SimpleCard {
                        val currentPosition = try {
                            com.example.ava.settings.CameraPosition.valueOf(
                                experimentalState?.cameraPosition ?: "FRONT"
                            )
                        } catch (e: Exception) {
                            com.example.ava.settings.CameraPosition.FRONT
                        }

                        val cameraOptions = buildList {
                            if (hasFrontCamera) add(com.example.ava.settings.CameraPosition.FRONT)
                            if (hasBackCamera) add(com.example.ava.settings.CameraPosition.BACK)
                        }

                        // The stored position defaults to FRONT even on back-only hardware, where
                        // the picker stays disabled — show the lens that will actually be bound
                        // (reported by @gilcu2, knoop7/Ava#163).
                        val availablePosition = when {
                            cameraOptions.isEmpty() || currentPosition in cameraOptions -> currentPosition
                            else -> cameraOptions.first()
                        }

                        val backCameraLabel = stringResource(R.string.settings_camera_back)
                        val frontCameraLabel = stringResource(R.string.settings_camera_front)

                        SelectSetting(
                            name = stringResource(R.string.settings_camera_position),
                            selected = availablePosition,
                            items = cameraOptions,
                            enabled = interactionsEnabled && cameraOptions.size > 1,
                            key = { it.name },
                            value = {
                                when (it) {
                                    com.example.ava.settings.CameraPosition.BACK -> backCameraLabel
                                    com.example.ava.settings.CameraPosition.FRONT -> frontCameraLabel
                                    null -> ""
                                }
                            },
                            onConfirmRequest = {
                                if (it != null && interactionsEnabled) {
                                    coroutineScope.launch {
                                        viewModel.saveCameraPosition(it)
                                    }
                                }
                            }
                        )

                        SettingsDivider()

                        val currentOrientation = try {
                            com.example.ava.settings.CameraOrientation.valueOf(
                                experimentalState?.cameraOrientation ?: "AUTO"
                            )
                        } catch (e: Exception) {
                            com.example.ava.settings.CameraOrientation.AUTO
                        }

                        val orientationOptions = listOf(
                            com.example.ava.settings.CameraOrientation.AUTO,
                            com.example.ava.settings.CameraOrientation.PORTRAIT,
                            com.example.ava.settings.CameraOrientation.PORTRAIT_FLIP,
                            com.example.ava.settings.CameraOrientation.LANDSCAPE,
                            com.example.ava.settings.CameraOrientation.LANDSCAPE_FLIP
                        )

                        val autoLabel = stringResource(R.string.settings_orientation_auto)
                        val portraitLabel = stringResource(R.string.settings_orientation_portrait)
                        val portraitFlipLabel = stringResource(R.string.settings_camera_orientation_portrait_flip)
                        val landscapeLabel = stringResource(R.string.settings_orientation_landscape)
                        val landscapeFlipLabel = stringResource(R.string.settings_camera_orientation_landscape_flip)

                        SelectSetting(
                            name = stringResource(R.string.settings_camera_orientation),
                            selected = currentOrientation,
                            items = orientationOptions,
                            enabled = interactionsEnabled,
                            key = { it.name },
                            value = {
                                when (it) {
                                    com.example.ava.settings.CameraOrientation.AUTO -> autoLabel
                                    com.example.ava.settings.CameraOrientation.PORTRAIT -> portraitLabel
                                    com.example.ava.settings.CameraOrientation.PORTRAIT_FLIP -> portraitFlipLabel
                                    com.example.ava.settings.CameraOrientation.LANDSCAPE -> landscapeLabel
                                    com.example.ava.settings.CameraOrientation.LANDSCAPE_FLIP -> landscapeFlipLabel
                                    null -> ""
                                }
                            },
                            onConfirmRequest = {
                                if (it != null && interactionsEnabled) {
                                    coroutineScope.launch {
                                        viewModel.saveCameraOrientation(it)
                                    }
                                }
                            }
                        )

                        if (currentMode == CameraMode.SNAPSHOT) {
                            SettingsDivider()

                            val currentSize = experimentalState?.imageSize ?: 500
                            val sizeOptions = listOf(0, 500, 720, 1080)

                            val sizeOriginalLabel = stringResource(R.string.settings_image_size_original)
                            val size500Label = stringResource(R.string.settings_image_size_500)
                            val size720Label = stringResource(R.string.settings_image_size_720)
                            val size1080Label = stringResource(R.string.settings_image_size_1080)

                            SelectSetting(
                                name = stringResource(R.string.settings_image_size),
                                selected = currentSize,
                                items = sizeOptions,
                                enabled = interactionsEnabled,
                                key = { it.toString() },
                                value = {
                                    when (it) {
                                        0 -> sizeOriginalLabel
                                        500 -> size500Label
                                        720 -> size720Label
                                        1080 -> size1080Label
                                        null -> ""
                                        else -> "${it}×${it}"
                                    }
                                },
                                onConfirmRequest = {
                                    if (it != null && interactionsEnabled) {
                                        coroutineScope.launch {
                                            viewModel.saveImageSize(it)
                                        }
                                    }
                                }
                            )
                        }

                        if (currentMode == CameraMode.VIDEO) {
                            SettingsDivider()

                            val currentFps = experimentalState?.videoFps ?: 5
                            val fpsOptions = listOf(1, 2, 3, 5, 8, 10, 15)
                            val fpsFormat = stringResource(R.string.settings_video_fps_format)

                            SelectSetting(
                                name = stringResource(R.string.settings_video_fps),
                                selected = currentFps,
                                items = fpsOptions,
                                enabled = interactionsEnabled,
                                key = { it.toString() },
                                value = { fps ->
                                    if (fps == null) "" else String.format(fpsFormat, fps)
                                },
                                onConfirmRequest = {
                                    if (it != null && interactionsEnabled) {
                                        coroutineScope.launch {
                                            viewModel.saveVideoFps(it)
                                        }
                                    }
                                }
                            )

                            SettingsDivider()

                            val currentResolution = experimentalState?.videoResolution ?: 480
                            val resolutionOptions = listOf(240, 360, 480, 720)

                            val res240Label = stringResource(R.string.settings_video_resolution_240)
                            val res360Label = stringResource(R.string.settings_video_resolution_360)
                            val res480Label = stringResource(R.string.settings_video_resolution_480)
                            val res720Label = stringResource(R.string.settings_video_resolution_720)

                            SelectSetting(
                                name = stringResource(R.string.settings_video_resolution),
                                selected = currentResolution,
                                items = resolutionOptions,
                                enabled = interactionsEnabled,
                                key = { it.toString() },
                                value = {
                                    when (it) {
                                        240 -> res240Label
                                        360 -> res360Label
                                        480 -> res480Label
                                        720 -> res720Label
                                        null -> ""
                                        else -> "${it}p"
                                    }
                                },
                                onConfirmRequest = {
                                    if (it != null && interactionsEnabled) {
                                        coroutineScope.launch {
                                            viewModel.saveVideoResolution(it)
                                        }
                                    }
                                }
                            )

                            SettingsDivider()

                            SettingRow(
                                label = stringResource(R.string.settings_person_detection),
                                subLabel = stringResource(R.string.settings_person_detection_desc)
                            ) {
                                ModernSwitch(
                                    checked = experimentalState?.personDetectionEnabled ?: false,
                                    enabled = interactionsEnabled,
                                    onCheckedChange = { enabled ->
                                        if (!interactionsEnabled) return@ModernSwitch
                                        coroutineScope.launch {
                                            viewModel.savePersonDetectionEnabled(enabled)
                                        }
                                    }
                                )
                            }

                            if (experimentalState?.personDetectionEnabled == true) {
                                SettingsDivider()

                                SettingRow(
                                    label = stringResource(R.string.settings_face_box),
                                    subLabel = stringResource(R.string.settings_face_box_desc)
                                ) {
                                    ModernSwitch(
                                        checked = experimentalState?.faceBoxEnabled ?: true,
                                        enabled = interactionsEnabled,
                                        onCheckedChange = { enabled ->
                                            if (!interactionsEnabled) return@ModernSwitch
                                            coroutineScope.launch {
                                                viewModel.saveFaceBoxEnabled(enabled)
                                            }
                                        }
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
