package com.ethran.notable.ui.views

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.design.NCodeBlock
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NTopBar
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.theme.InkaTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.RefreshCw
import com.ethran.notable.ui.viewmodels.DeviceSnapshot
import com.ethran.notable.ui.viewmodels.StrokeStyleInfo
import com.ethran.notable.ui.viewmodels.SystemInformationViewModel
import java.io.File


object SystemInformationDestination : NavigationDestination {
    override val route = "SystemInformationView"
}


/**
 * THIS FILE WAS WRITTEN BY AI.
 *
 * Monochrome system information view tailored for e-ink devices.
 * Priority of sections:
 * 1) Basic system info
 * 2) Screen info
 * 3) Writing info (includes Input/Pen and StrokeStyle parameters)
 * 4) Rest (connectivity, storage, fonts, misc)
 *
 * The view is hardened against exceptions. All calls are safe; failures are shown in an "Errors" section.
 * Includes a Refresh button and auto-refresh on focus (Activity resume).
 */
@Composable
fun SystemInformationView(
    onBack: () -> Unit, viewModel: SystemInformationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-refresh on Resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SystemInformationContent(
        uiState = uiState,
        onBack = onBack,
        onRefresh = { viewModel.refresh(context) },
    )
}

@Composable
fun SystemInformationContent(
    uiState: SystemInformationViewModel.SystemInfoUiState?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val c = NotableTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.paper)
    ) {
        NTopBar(
            title = "System information",
            onBack = onBack,
            actions = { NIconButton(FeatherIcons.RefreshCw, "Refresh", onRefresh) },
        )

        if (uiState == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading device data…", style = NotableTheme.type.body, color = c.secondary)
            }
            return@Column
        }
        val info = uiState.snapshot
        val strokeInfo = uiState.strokeInfo

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                Modifier
                    .padding(horizontal = NotableSpace.gutter)
                    .padding(bottom = NotableSpace.xxl)
                    .widthIn(max = SettingsMaxWidth)
                    .fillMaxWidth()
            ) {
                // 0) DeviceInfoUtil
                NSection("Device info util") {
                    InfoRow("Device info", info.deviceInfoStr, maxLines = 10)
                    InfoRow("Kernel info", info.kernelInfo, maxLines = 10)
                    InfoRow("EMTP info", info.emtpInfo)
                    InfoRow("VCom info", info.vcomInfo)
                    InfoRow("Onyx device", info.isOnyxDevice.toYesNo())
                    InfoRow("Color device", info.isColorDevice.toYesNo())
                    InfoRow("CPU serial", info.cpuSerial)
                    InfoRow("Resolution", "${info.resolutionX ?: "?"} x ${info.resolutionY ?: "?"}")
                }

                // 1) Basic system info
                NSection("Basic system info") {
                    InfoRow("Manufacturer", Build.MANUFACTURER)
                    InfoRow("Model", Build.MODEL)
                    InfoRow("Build type", Build.TYPE)
                    InfoRow("SDK", Build.VERSION.SDK_INT.toString())
                    InfoRow("Actual device class", info.actualDeviceClass)
                    InfoRow("Class hierarchy", info.deviceClassHierarchy)
                    InfoRow("System config prefix", info.systemConfigPrefix)
                    InfoRow("Eng build", info.isEngBuild.toYesNo())
                    InfoRow("Userdebug build", info.isUserDebugBuild.toYesNo())
                    InfoRow("Boot-up time (ms)", info.bootUpTimeMs?.toString())
                    InfoRow("Reset password supported", info.resetPasswordSupported.toYesNo())
                    InfoRow("Min password length", info.minPasswordLength?.toString())
                    InfoRow("Max password length", info.maxPasswordLength?.toString())
                }

                // 2) Screen info
                NSection("Screen info") {
                    InfoRow("EPD mode", info.epdMode?.name)
                    InfoRow("System default update mode", info.systemDefaultUpdateMode?.name)
                    InfoRow("App scope refresh mode", info.appScopeRefreshMode?.name)
                    InfoRow("In system fast mode", info.inSystemFastMode.toYesNo())
                    InfoRow("In app fast mode", info.inAppFastMode.toYesNo())
                    InfoRow("In fast mode", info.inFastMode.toYesNo())
                    InfoRow("Global contrast", info.globalContrast?.toString())
                    InfoRow("Dither threshold", info.ditherThreshold?.toString())
                    InfoRow("Color type", info.colorType?.toString())
                    InfoRow("Supports night mode", info.supportNightMode.toYesNo())
                    InfoRow("Supports wide color gamut", info.supportWideColorGamut.toYesNo())
                }

                // 3) Writing info (Input/Pen + Stroke Styles)
                NSection("Writing info") {
                    InfoRow("Touchpad enabled", info.touchpadEnabled.toYesNo())
                    InfoRow("Supports active pen", info.supportActivePen.toYesNo())
                    InfoRow("Active pen enabled", info.activePenEnabled.toYesNo())
                    InfoRow("Active pen battery", info.activePenBattery?.toString())
                    InfoRow("Active pen MAC", info.activePenMac)
                    InfoRow("Pen UI visibility enabled", info.penUIVisibilityEnabled.toYesNo())
                    InfoRow("Pen haptics enabled", info.penHapticEnabled.toYesNo())
                    // Touch / EPD geometry
                    InfoRow("Touch width", info.touchWidth?.toString())
                    InfoRow("Touch height", info.touchHeight?.toString())
                    InfoRow("Max touch pressure", info.maxTouchPressure?.toString())
                    InfoRow("EPD width", info.epdWidth?.toString())
                    InfoRow("EPD height", info.epdHeight?.toString())
                    InfoRow("Valid pen state", info.isValidPenState?.toString())
                }

                // Stroke style details (separate function builds this list)
                NSection("Stroke styles") {
                    strokeInfo.forEach { s ->
                        InfoRow("Style", s.styleName)
                        InfoRow("Parameters (${s.styleName})", s.parameters?.joinToString())
                        if (!s.extraNotes.isNullOrBlank()) {
                            InfoRow("Notes (${s.styleName})", s.extraNotes)
                        }
                    }
                }

                // 4) Rest
                NSection("Connectivity") {
                    InfoRow("Wi-Fi", info.hasWifi.toYesNo())
                    InfoRow("Bluetooth", info.hasBluetooth.toYesNo())
                    InfoRow("Audio", info.hasAudio.toYesNo())
                    InfoRow("Fixed Wi-Fi MAC", info.fixedWifiMac)
                    InfoRow("Bluetooth address", info.bluetoothAddress)
                    InfoRow("Encrypted device ID", info.encryptedDeviceId)
                }

                NSection("Light") {
                    InfoRow("Front light brightness", info.hasFrontLightBrightness.toYesNo())
                    InfoRow("CTM brightness", info.hasCTMBrightness.toYesNo())
                    InfoRow("Light on", info.isLightOn.toYesNo())
                    InfoRow("Front light min", info.frontLightMin?.toString())
                    InfoRow("Front light max", info.frontLightMax?.toString())
                    InfoRow("Front light default", info.frontLightDefault?.toString())
                    InfoRow("Front light config", info.frontLightConfigValue?.toString())
                    InfoRow("Warm light config", info.warmLightConfigValue?.toString())
                    InfoRow("Cold light config", info.coldLightConfigValue?.toString())
                    InfoRow("CTM available", info.checkCTM.toYesNo())
                    InfoRow("CTM BR default", info.brDefault?.toString())
                    InfoRow("CTM CT default", info.ctDefault?.toString())
                }

                NSection("Wireless charging") {
                    InfoRow("Supports wireless charging", info.supportWirelessCharging.toYesNo())
                    InfoRow("Wireless charge battery", info.wirelessChargingBattery?.toString())
                    InfoRow("Wireless charge state", info.wirelessChargingState?.toString())
                    InfoRow("Wireless chip ID", info.wirelessChargingChipId)
                    InfoRow("Wireless chip version", info.wirelessChargingChipVersion)
                }

                NSection("Multi-window") {
                    InfoRow("Origin multi-window", info.originMultiWindow.toYesNo())
                    InfoRow("Current multi-screen mode", info.currentMultiScreenMode?.toString())
                    InfoRow("Limited multi-screen mode", info.limitedMultiScreenMode.toYesNo())
                    InfoRow("Full-function multi-screen mode", info.fullFunctionMultiScreenMode.toYesNo())
                }

                NSection("Storage") {
                    InfoRow("Primary storage removable", info.primaryStorageRemovable.toYesNo())
                    InfoRow("Storage root", info.storageRoot?.path)
                    InfoRow("Removable SD dirs", info.removableSdDirs?.joinToString { it.path })
                    InfoRow("USB storage present", info.usbStoragePresent.toYesNo())
                }

                NSection("System and fonts") {
                    InfoRow("CPU ID", info.cpuId ?: "-")
                    InfoRow("Supports external SD", info.supportExternalSd.toYesNo())
                    InfoRow("Supports font hot reload", info.supportFontHotReload.toYesNo())
                    info.systemFontFamilyMap?.entries
                        ?.take(6)
                        ?.forEach { (family, path) ->
                            InfoRow("Font: $family", path)
                        }
                }

                // Errors section: show any failures for transparency
                if (info.errors.isNotEmpty()) {
                    NSection("Errors") {
                        NCodeBlock(info.errors.joinToString("\n\n"), maxHeight = 480.dp)
                    }
                }
            }
        }
    }
}

/** A key and its value on one line, a hairline under it. */
@Composable
private fun InfoRow(label: String, value: String?, maxLines: Int = 3) {
    val c = NotableTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NotableSize.control)
                .padding(vertical = NotableSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = NotableTheme.type.body,
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = NotableSpace.lg),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value ?: "-",
                style = NotableTheme.type.body,
                color = c.secondary,
                modifier = Modifier.weight(1f),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        NDivider()
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
fun SystemInformationPreview() {
    val mockSnapshot = DeviceSnapshot(
        deviceInfoStr = "Mock OS Version",
        kernelInfo = "Mock Kernel",
        emtpInfo = "Mock EMTP",
        vcomInfo = "1.5 V",
        cpuSerial = "123456",
        resolutionX = 1404,
        resolutionY = 1872,
        isOnyxDevice = true,
        isColorDevice = false,

        // Screen
        epdMode = null,
        systemDefaultUpdateMode = null,
        appScopeRefreshMode = null,
        inSystemFastMode = true,
        inAppFastMode = false,
        inFastMode = true,
        globalContrast = 10,
        ditherThreshold = 5,
        colorType = 1,
        supportNightMode = true,
        supportWideColorGamut = false,

        // Writing/Input
        touchpadEnabled = true,
        supportActivePen = true,
        activePenEnabled = true,
        activePenBattery = 85,
        activePenMac = "00:11:22:33:44:55",
        penUIVisibilityEnabled = true,
        penHapticEnabled = false,
        touchWidth = 1080f,
        touchHeight = 1440f,
        maxTouchPressure = 1.0f,
        epdWidth = 1072f,
        epdHeight = 1448f,
        isValidPenState = true,

        // Light
        hasFrontLightBrightness = true,
        hasCTMBrightness = true,
        isLightOn = true,
        frontLightMin = 0,
        frontLightMax = 100,
        frontLightDefault = 50,
        frontLightConfigValue = 60,
        warmLightConfigValue = 40,
        coldLightConfigValue = 30,
        checkCTM = true,
        brDefault = 50,
        ctDefault = 45,

        // Basic system
        powerSavedMode = false,
        hallControlEnabled = true,
        bootUpTimeMs = 15000L,
        isEngBuild = false,
        isUserDebugBuild = false,
        resetPasswordSupported = true,
        systemConfigPrefix = "sys_",
        minPasswordLength = 6,
        maxPasswordLength = 16,

        // Connectivity
        hasAudio = true,
        hasWifi = true,
        hasBluetooth = true,
        fixedWifiMac = "AA:BB:CC:DD:EE:FF",
        bluetoothAddress = "11:22:33:44:55:66",
        encryptedDeviceId = "encrypted-device-id-123",

        // Wireless charging
        supportWirelessCharging = false,
        wirelessChargingBattery = null,
        wirelessChargingState = null,
        wirelessChargingChipId = null,
        wirelessChargingChipVersion = null,

        // Multi-Window
        originMultiWindow = true,
        currentMultiScreenMode = 1,
        limitedMultiScreenMode = false,
        fullFunctionMultiScreenMode = true,

        // Storage
        primaryStorageRemovable = false,
        storageRoot = File("/storage/emulated/0"),
        removableSdDirs = listOf(File("/storage/sdcard1")),
        usbStoragePresent = false,

        // Fonts / Misc
        cpuId = "CPU123456789",
        supportExternalSd = true,
        supportFontHotReload = false,
        systemFontFamilyMap = mapOf(
            "sans-serif" to "Roboto", "serif" to "Noto Serif"
        ),

        errors = emptyList()
    )

    InkaTheme {
        SystemInformationContent(
            uiState = SystemInformationViewModel.SystemInfoUiState(
                snapshot = mockSnapshot,
                strokeInfo = listOf(StrokeStyleInfo(1, "PENCIL", listOf(1f, 2f)))
            ),
            onBack = {},
            onRefresh = {},
        )
    }
}


private fun Boolean?.toYesNo(): String? = when (this) {
    null -> "--"
    true -> "Yes"
    false -> "No"
}
