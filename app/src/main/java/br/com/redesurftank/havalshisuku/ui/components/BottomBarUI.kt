package br.com.redesurftank.havalshisuku.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import br.com.redesurftank.havalshisuku.R
import br.com.redesurftank.havalshisuku.managers.*
import br.com.redesurftank.havalshisuku.models.*
import br.com.redesurftank.havalshisuku.ui.theme.Michroma
import br.com.redesurftank.havalshisuku.utils.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*

private val commonTextStyle =
        TextStyle(
                color = Color.White,
                fontSize = 20.sp,
                fontFamily = Michroma,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
        )

private val labelStyle =
        TextStyle(
                color = Color.LightGray,
                fontSize = 10.sp,
                fontFamily = Michroma,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
        )

private fun String?.toComposeColor(): Color {
        if (this == null || !this.startsWith("#")) return Color.White
        return try {
                Color(android.graphics.Color.parseColor(this))
        } catch (_: Exception) {
                Color.White
        }
}

@Composable
fun BottomBarContent() {
        val serviceManager = ServiceManager.getInstance()
        val scope = rememberCoroutineScope()

        // States for AC, Volume, etc.
        var driverTemp by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.getValue())
                                ?: "--"
                )
        }
        var passTemp by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_HVAC_PASS_TEMPERATURE.getValue())
                                ?: "--"
                )
        }
        var volume by remember {
                mutableIntStateOf(
                        serviceManager
                                .getData(CarConstants.SYS_SETTINGS_AUDIO_MEDIA_VOLUME.getValue())
                                ?.toIntOrNull()
                                ?: 0
                )
        }
        var powerModel by remember {
                mutableStateOf(
                        serviceManager.getData(
                                CarConstants.CAR_EV_SETTING_POWER_MODEL_CONFIG.getValue()
                        )
                                ?: "0"
                )
        }
        var energyRecovery by remember {
                mutableStateOf(
                        serviceManager.getData(
                                CarConstants.CAR_EV_SETTING_ENERGY_RECOVERY_LEVEL.getValue()
                        )
                                ?: "0"
                )
        }
        var driveMode by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_DRIVE_SETTING_DRIVE_MODE.getValue())
                                ?: "0"
                )
        }
        var steeringMode by remember {
                mutableStateOf(
                        serviceManager.getData(
                                CarConstants.CAR_DRIVE_SETTING_STEERING_WHEEL_ASSIST_MODE.getValue()
                        )
                                ?: "0"
                )
        }
        var fanSpeed by remember {
                mutableIntStateOf(
                        serviceManager
                                .getData(CarConstants.CAR_HVAC_FAN_SPEED.getValue())
                                ?.toIntOrNull()
                                ?: 1
                )
        }
        var hvacPower by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_HVAC_POWER_MODE.getValue()) ?: "1"
                )
        }
        var acSync by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_HVAC_SYNC_ENABLE.getValue()) ?: "0"
                )
        }
        var acAuto by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_HVAC_AUTO_ENABLE.getValue()) ?: "0"
                )
        }

        // Update states when data changes
        DisposableEffect(Unit) {
                val listener =
                        object : br.com.redesurftank.havalshisuku.listeners.IDataChanged {
                                override fun onDataChanged(key: String, value: String?) {
                                        if (value == null) return
                                        when (key) {
                                                CarConstants.CAR_HVAC_DRIVER_TEMPERATURE
                                                        .getValue() -> driverTemp = value
                                                CarConstants.CAR_HVAC_PASS_TEMPERATURE.getValue() ->
                                                        passTemp = value
                                                CarConstants.SYS_SETTINGS_AUDIO_MEDIA_VOLUME
                                                        .getValue() ->
                                                        volume = value.toIntOrNull() ?: volume
                                                CarConstants.CAR_EV_SETTING_POWER_MODEL_CONFIG
                                                        .getValue() -> powerModel = value
                                                CarConstants.CAR_EV_SETTING_ENERGY_RECOVERY_LEVEL
                                                        .getValue() -> energyRecovery = value
                                                CarConstants.CAR_DRIVE_SETTING_DRIVE_MODE
                                                        .getValue() -> driveMode = value
                                                CarConstants
                                                        .CAR_DRIVE_SETTING_STEERING_WHEEL_ASSIST_MODE
                                                        .getValue() -> steeringMode = value
                                                CarConstants.CAR_HVAC_FAN_SPEED.getValue() ->
                                                        fanSpeed = value.toIntOrNull() ?: fanSpeed
                                                CarConstants.CAR_HVAC_POWER_MODE.getValue() ->
                                                        hvacPower = value
                                                CarConstants.CAR_HVAC_SYNC_ENABLE.getValue() ->
                                                        acSync = value
                                                CarConstants.CAR_HVAC_AUTO_ENABLE.getValue() ->
                                                        acAuto = value
                                        }
                                }
                        }
                serviceManager.addDataChangedListener(listener)
                onDispose { serviceManager.removeDataChangedListener(listener) }
        }

        Box(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                contentAlignment = Alignment.BottomCenter
        ) {
                if (BottomBarState.isVisible) {
                        Surface(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .height(60.dp)
                                                // Swipe-down gesture: if user drags down > 20dp,
                                                // hide the bar.
                                                // Taps and small movements pass through to buttons
                                                // untouched.
                                                .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                                while (true) {
                                                                        val down =
                                                                                awaitFirstDown(
                                                                                        requireUnconsumed =
                                                                                                false
                                                                                )
                                                                        var totalDragY = 0f

                                                                        do {
                                                                                val event =
                                                                                        awaitPointerEvent()
                                                                                val change =
                                                                                        event.changes
                                                                                                .first()
                                                                                totalDragY +=
                                                                                        change.position
                                                                                                .y -
                                                                                                change.previousPosition
                                                                                                        .y

                                                                                // Confirmed
                                                                                // downward swipe
                                                                                // past bottom →
                                                                                // hide
                                                                                // bar
                                                                                if (totalDragY > 20f
                                                                                ) {
                                                                                        event.changes
                                                                                                .forEach {
                                                                                                        it.consume()
                                                                                                }
                                                                                        // Consume
                                                                                        // remaining
                                                                                        // pointer
                                                                                        // events
                                                                                        do {
                                                                                                val ev2 =
                                                                                                        awaitPointerEvent()
                                                                                                ev2.changes
                                                                                                        .forEach {
                                                                                                                it.consume()
                                                                                                        }
                                                                                        } while (ev2.changes
                                                                                                .any {
                                                                                                        it.pressed
                                                                                                })
                                                                                        BottomBarState
                                                                                                .isVisible =
                                                                                                false
                                                                                        break
                                                                                }
                                                                        } while (event.changes.any {
                                                                                it.pressed
                                                                        })
                                                                        // If finger lifted without
                                                                        // crossing threshold → do
                                                                        // nothing (button handles
                                                                        // it)
                                                                }
                                                        }
                                                },
                                color = Color.Black,
                                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
                                tonalElevation = 0.dp
                        ) {
                                // Use BoxWithConstraints to get actual measured width
                                androidx.compose.foundation.layout.BoxWithConstraints(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.CenterEnd
                                ) {
                                        val density =
                                                androidx.compose.ui.platform.LocalDensity.current
                                                        .density
                                        val actualWidthPx = constraints.maxWidth

                                        // Dynamic padding: 150px when full 1920px, reduced when
                                        // narrower
                                        // Threshold: 1820px (= 1920 - 100). Below this, no padding.
                                        val thresholdPx = (1820 * density).toInt()
                                        val compensationPx =
                                                (actualWidthPx - thresholdPx).coerceAtLeast(0)
                                        val horizontalOffsetCompensation =
                                                (compensationPx / density).dp

                                        // Troubleshoot logging
                                        android.util.Log.d(
                                                "BottomBarUI",
                                                "Width - " +
                                                        "ActualWidthPx: $actualWidthPx, " +
                                                        "Density: $density, " +
                                                        "ThresholdPx: $thresholdPx, " +
                                                        "CompensationPx: $compensationPx, " +
                                                        "PaddingDp: $horizontalOffsetCompensation"
                                        )

                                        Row(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .fillMaxHeight()
                                                                .padding(
                                                                        start =
                                                                                horizontalOffsetCompensation,
                                                                        end = 8.dp
                                                                ),
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                // 1. App Switcher (11%)
                                                Box(modifier = Modifier.weight(0.11f)) {
                                                        AppSwitcherSection()
                                                }

                                                val isACEnabled = hvacPower == "1"

                                                // 2. AC Driver (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        TempControlSection(
                                                                "Motorista",
                                                                driverTemp,
                                                                isACEnabled
                                                        ) { delta ->
                                                                val newTemp =
                                                                        (driverTemp.toFloatOrNull()
                                                                                ?: 22.0f) + delta
                                                                serviceManager.updateData(
                                                                        CarConstants
                                                                                .CAR_HVAC_DRIVER_TEMPERATURE
                                                                                .getValue(),
                                                                        String.format(
                                                                                java.util.Locale.US,
                                                                                "%.1f",
                                                                                newTemp
                                                                        )
                                                                )
                                                        }
                                                }

                                                // 3. Controls Group (Back, Settings) (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) { ControlsSection(scope) }

                                                // 4. AC Fan Speed (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        FanControlSection(fanSpeed, true) { delta ->
                                                                val calculatedSpeed =
                                                                        (fanSpeed + delta).coerceIn(
                                                                                0,
                                                                                7
                                                                        )
                                                                serviceManager.updateData(
                                                                        CarConstants
                                                                                .CAR_HVAC_FAN_SPEED
                                                                                .getValue(),
                                                                        calculatedSpeed.toString()
                                                                )

                                                                if (calculatedSpeed == 0 &&
                                                                                hvacPower == "1"
                                                                ) {
                                                                        serviceManager.updateData(
                                                                                CarConstants
                                                                                        .CAR_HVAC_POWER_MODE
                                                                                        .getValue(),
                                                                                "0"
                                                                        )
                                                                } else if (calculatedSpeed > 0 &&
                                                                                hvacPower == "0"
                                                                ) {
                                                                        serviceManager.updateData(
                                                                                CarConstants
                                                                                        .CAR_HVAC_POWER_MODE
                                                                                        .getValue(),
                                                                                "1"
                                                                        )
                                                                }
                                                        }
                                                }

                                                // 5. AC Sync/Auto (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        Row(
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(20.dp),
                                                                verticalAlignment =
                                                                        Alignment.CenterVertically
                                                        ) {
                                                                ACControlButton(
                                                                        icon = Icons.Default.Sync,
                                                                        label = "Sync",
                                                                        isActive = acSync == "1",
                                                                        isEnabled = isACEnabled
                                                                ) {
                                                                        val next =
                                                                                if (acSync == "1")
                                                                                        "0"
                                                                                else "1"
                                                                        serviceManager.updateData(
                                                                                CarConstants
                                                                                        .CAR_HVAC_SYNC_ENABLE
                                                                                        .getValue(),
                                                                                next
                                                                        )
                                                                }
                                                                ACControlButton(
                                                                        icon =
                                                                                Icons.Default
                                                                                        .AutoMode,
                                                                        label = "Auto",
                                                                        isActive = acAuto == "1",
                                                                        isEnabled = isACEnabled
                                                                ) {
                                                                        val next =
                                                                                if (acAuto == "1")
                                                                                        "0"
                                                                                else "1"
                                                                        serviceManager.updateData(
                                                                                CarConstants
                                                                                        .CAR_HVAC_AUTO_ENABLE
                                                                                        .getValue(),
                                                                                next
                                                                        )
                                                                }
                                                        }
                                                }

                                                // 6. Volume (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        VolumeControlSection(
                                                                label = "Volume",
                                                                volume
                                                        ) { delta ->
                                                                val newVol =
                                                                        (volume + delta).coerceIn(
                                                                                0,
                                                                                30
                                                                        )
                                                                serviceManager.updateData(
                                                                        CarConstants
                                                                                .SYS_SETTINGS_AUDIO_MEDIA_VOLUME
                                                                                .getValue(),
                                                                        newVol.toString()
                                                                )
                                                        }
                                                }

                                                // 7. AC Passenger (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        TempControlSection(
                                                                "Passageiro",
                                                                passTemp,
                                                                isACEnabled
                                                        ) { delta ->
                                                                val newTemp =
                                                                        (passTemp.toFloatOrNull()
                                                                                ?: 22.0f) + delta
                                                                serviceManager.updateData(
                                                                        CarConstants
                                                                                .CAR_HVAC_PASS_TEMPERATURE
                                                                                .getValue(),
                                                                        String.format(
                                                                                java.util.Locale.US,
                                                                                "%.1f",
                                                                                newTemp
                                                                        )
                                                                )
                                                        }
                                                }

                                                // 8. Override Section (5%)
                                                Box(
                                                        modifier =
                                                                Modifier.weight(0.05f)
                                                                        // Use raw pointerInput
                                                                        // instead of
                                                                        // IconButton/clickable
                                                                        // so it works even in
                                                                        // YouTube immersive mode
                                                                        // where
                                                                        // the
                                                                        // system gesture navigation
                                                                        // consumes touch events.
                                                                        .pointerInput(Unit) {
                                                                                awaitPointerEventScope {
                                                                                        while (true) {
                                                                                                val down =
                                                                                                        awaitFirstDown(
                                                                                                                requireUnconsumed =
                                                                                                                        false
                                                                                                        )
                                                                                                // Wait for finger lift
                                                                                                var totalDrag =
                                                                                                        0f
                                                                                                do {
                                                                                                        val event =
                                                                                                                awaitPointerEvent()
                                                                                                        val change =
                                                                                                                event.changes
                                                                                                                        .first()
                                                                                                        totalDrag +=
                                                                                                                (change.position -
                                                                                                                                change.previousPosition)
                                                                                                                        .getDistance()
                                                                                                } while (event.changes
                                                                                                        .any {
                                                                                                                it.pressed
                                                                                                        })
                                                                                                // Only trigger if it was a tap (not a
                                                                                                // drag)
                                                                                                if (totalDrag <
                                                                                                                30f
                                                                                                ) {
                                                                                                        android.util
                                                                                                                .Log
                                                                                                                .e(
                                                                                                                        "OVERSCAN_DEBUG",
                                                                                                                        "Icon Clicked! Current State: ${BottomBarState.isOverrideMenuExpanded}"
                                                                                                                )
                                                                                                        BottomBarState
                                                                                                                .isOverrideMenuExpanded =
                                                                                                                !BottomBarState
                                                                                                                        .isOverrideMenuExpanded
                                                                                                        if (BottomBarState
                                                                                                                        .isOverrideMenuExpanded
                                                                                                        ) {
                                                                                                                BottomBarState
                                                                                                                        .isMenuExpanded =
                                                                                                                        false
                                                                                                                BottomBarState
                                                                                                                        .isSettingsMenuExpanded =
                                                                                                                        false
                                                                                                        }
                                                                                                        android.util
                                                                                                                .Log
                                                                                                                .e(
                                                                                                                        "OVERSCAN_DEBUG",
                                                                                                                        "New State: ${BottomBarState.isOverrideMenuExpanded}"
                                                                                                                )
                                                                                                }
                                                                                        }
                                                                                }
                                                                        },
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        Icon(
                                                                Icons.Default.SwapVert,
                                                                contentDescription = null,
                                                                tint =
                                                                        Color.White.copy(
                                                                                alpha = 0.8f
                                                                        )
                                                        )
                                                }
                                        }
                                }
                        }
                }

                if (!BottomBarState.isVisible) {
                        Box(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .height(60.dp)
                                                .align(Alignment.BottomCenter)
                                                .background(Color.Transparent)
                                                // Swipe-up gesture: if user drags upward > 30dp,
                                                // show the bar
                                                .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                                while (true) {
                                                                        awaitFirstDown(
                                                                                requireUnconsumed =
                                                                                        false
                                                                        )
                                                                        var totalDragY = 0f

                                                                        do {
                                                                                val event =
                                                                                        awaitPointerEvent()
                                                                                val change =
                                                                                        event.changes
                                                                                                .first()
                                                                                totalDragY +=
                                                                                        change.position
                                                                                                .y -
                                                                                                change.previousPosition
                                                                                                        .y

                                                                                // Confirmed upward
                                                                                // swipe → show bar
                                                                                if (totalDragY <
                                                                                                -30f
                                                                                ) {
                                                                                        event.changes
                                                                                                .forEach {
                                                                                                        it.consume()
                                                                                                }
                                                                                        do {
                                                                                                val ev2 =
                                                                                                        awaitPointerEvent()
                                                                                                ev2.changes
                                                                                                        .forEach {
                                                                                                                it.consume()
                                                                                                        }
                                                                                        } while (ev2.changes
                                                                                                .any {
                                                                                                        it.pressed
                                                                                                })
                                                                                        BottomBarState
                                                                                                .isVisible =
                                                                                                true
                                                                                        break
                                                                                }
                                                                        } while (event.changes.any {
                                                                                it.pressed
                                                                        })
                                                                }
                                                        }
                                                }
                        )
                }
        }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun getSubstituteIconVector(substituteIcon: String?): ImageVector? {
        return when (substituteIcon) {
                "nav" -> Icons.Default.Place
                "music" -> Icons.Default.PlayArrow
                "video" -> Icons.Default.Movie
                "settings" -> Icons.Default.Tune
                "haval" -> Icons.Default.DirectionsCar
                "game" -> Icons.Default.SportsEsports
                "tv" -> Icons.Default.Tv
                "phone" -> Icons.Default.Phone
                "chat" -> Icons.Default.Chat
                "map_alt" -> Icons.Default.Map
                else -> null
        }
}

@Composable
fun AppSwitcherSection() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val configs = br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getAllConfigs()

        // Initialize if empty
        if (br.com.redesurftank.havalshisuku.models.BottomBarState.selectedPackage.isEmpty()) {
                br.com.redesurftank.havalshisuku.models.BottomBarState.selectedPackage =
                        configs.firstOrNull()?.packageName ?: ""
        }

        val selectedPackage = br.com.redesurftank.havalshisuku.models.BottomBarState.selectedPackage
        val showMenu = br.com.redesurftank.havalshisuku.models.BottomBarState.isMenuExpanded

        val selectedConfig = configs.find { it.packageName == selectedPackage }
        val substituteIconVector = getSubstituteIconVector(selectedConfig?.substituteIcon)

        Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                        onClick = {
                                scope.launch {
                                        br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                                .getOrCreateDefaultConfig(context, selectedPackage)
                                                ?.let {
                                                        br.com.redesurftank.havalshisuku.managers
                                                                .DisplayAppLauncher.sendToDisplay(
                                                                it
                                                        )
                                                }
                                }
                        },
                        modifier = Modifier.width(70.dp).fillMaxHeight()
                ) {
                        Icon(
                                Icons.Default.KeyboardArrowLeft,
                                contentDescription = "Cluster",
                                tint = Color.White
                        )
                }
                Box(
                        modifier =
                                Modifier.size(55.dp)
                                        .background(Color.Black, RoundedCornerShape(4.dp))
                                        .pointerInput(showMenu, selectedPackage) {
                                                detectTapGestures(
                                                        onTap = {
                                                                br.com.redesurftank.havalshisuku
                                                                        .models.BottomBarState
                                                                        .isMenuExpanded = !showMenu
                                                        },
                                                        onDoubleTap = {
                                                                if (selectedPackage.isNotEmpty()) {
                                                                        br.com.redesurftank
                                                                                .havalshisuku.models
                                                                                .BottomBarState
                                                                                .isMenuExpanded =
                                                                                false
                                                                        scope.launch {
                                                                                br.com.redesurftank
                                                                                        .havalshisuku
                                                                                        .managers
                                                                                        .DisplayAppLauncher
                                                                                        .getOrCreateDefaultConfig(
                                                                                                context,
                                                                                                selectedPackage
                                                                                        )
                                                                                br.com.redesurftank
                                                                                        .havalshisuku
                                                                                        .managers
                                                                                        .DisplayAppLauncher
                                                                                        .launchAnyApp(
                                                                                                context,
                                                                                                selectedPackage
                                                                                        )
                                                                        }
                                                                }
                                                        }
                                                )
                                        },
                        contentAlignment = Alignment.Center
                ) {
                        if (selectedConfig?.substituteIcon == "youtube" ||
                                        selectedConfig?.substituteIcon == "youtube_music" ||
                                        selectedConfig?.substituteIcon == "gwm"
                        ) {
                                Image(
                                        painter =
                                                painterResource(
                                                        id =
                                                                when (selectedConfig.substituteIcon
                                                                ) {
                                                                        "youtube" ->
                                                                                R.drawable
                                                                                        .ic_youtube_default
                                                                        "youtube_music" ->
                                                                                R.drawable
                                                                                        .ic_youtube_music_default
                                                                        "gwm" -> R.drawable.ic_gwm
                                                                        else ->
                                                                                R.drawable
                                                                                        .ic_youtube_default
                                                                }
                                                ),
                                        contentDescription = "App Icon",
                                        modifier = Modifier.size(32.dp)
                                )
                        } else if (substituteIconVector != null) {
                                val iconTint = selectedConfig?.iconColor.toComposeColor()
                                Icon(
                                        substituteIconVector,
                                        contentDescription = "App Icon",
                                        tint = iconTint,
                                        modifier = Modifier.size(32.dp)
                                )
                        } else if (selectedPackage.isNotEmpty()) {
                                val appInfo =
                                        remember(selectedPackage) {
                                                br.com.redesurftank.havalshisuku.managers
                                                        .DisplayAppLauncher.resolveAppInfo(
                                                        context,
                                                        selectedPackage,
                                                        selectedConfig?.customName
                                                )
                                        }

                                AsyncImage(
                                        model =
                                                ImageRequest.Builder(context)
                                                        .data(appInfo.icon)
                                                        .build(),
                                        contentDescription = "App Icon",
                                        modifier = Modifier.size(40.dp)
                                )
                        } else {
                                Icon(
                                        Icons.Default.Layers,
                                        contentDescription = "Apps",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                )
                        }
                }

                IconButton(
                        onClick = {
                                scope.launch {
                                        br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                                .bringAllToMainDisplay()
                                        if (selectedPackage.isNotEmpty()) {
                                                br.com.redesurftank.havalshisuku.managers
                                                        .DisplayAppLauncher
                                                        .getOrCreateDefaultConfig(
                                                                context,
                                                                selectedPackage
                                                        )
                                                br.com.redesurftank.havalshisuku.managers
                                                        .DisplayAppLauncher.launchAnyApp(
                                                        context,
                                                        selectedPackage
                                                )
                                        }
                                }
                        },
                        modifier = Modifier.width(70.dp).fillMaxHeight()
                ) {
                        Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = "MMI",
                                tint = Color.White
                        )
                }
        }
}

@Composable
fun AppMenuContent() {
        val initialConfigs = remember {
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getAllConfigs()
        }
        val configsList = remember {
                mutableStateListOf<br.com.redesurftank.havalshisuku.models.DisplayAppConfig>()
                        .apply { addAll(initialConfigs) }
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val currentPkg = BottomBarState.currentPackage
        val isCurrentInConfigs = configsList.any { it.packageName == currentPkg }
        val showAddButton =
                !isCurrentInConfigs && currentPkg.isNotEmpty() && currentPkg != context.packageName

        // Track item positions for drag and drop
        val itemBounds = remember { mutableMapOf<Int, Rect>() }
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

        fun findIndexAtOffset(offset: Offset): Int? {
                val rootOffset = containerCoordinates?.localToRoot(offset) ?: offset
                return itemBounds.entries.find { it.value.contains(rootOffset) }?.key
        }

        Box(
                modifier =
                        Modifier.background(
                                        Color(0xFF13151A).copy(alpha = 0.95f),
                                        RoundedCornerShape(12.dp)
                                )
                                .border(1.dp, Color(0xFF1D2430), RoundedCornerShape(12.dp))
                                .fillMaxWidth(0.25f)
                                .padding(16.dp)
                                .pointerInput(Unit) {
                                        detectTapGestures {
                                                BottomBarState.isDeleteModeEnabled = false
                                        }
                                }
        ) {
                val totalItems = configsList.size + (if (showAddButton) 1 else 0)
                val columns = 3
                val rows = (totalItems + columns - 1) / columns

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Header
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                        Text(
                                                text =
                                                        if (BottomBarState.isDeleteModeEnabled)
                                                                "Organizar"
                                                        else "Aplicativos",
                                                color =
                                                        if (BottomBarState.isDeleteModeEnabled)
                                                                Color(0xFF4CAF50)
                                                        else Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                        )
                                        if (BottomBarState.isDeleteModeEnabled) {
                                                Box(
                                                        modifier =
                                                                Modifier.clip(
                                                                                RoundedCornerShape(
                                                                                        16.dp
                                                                                )
                                                                        )
                                                                        .background(
                                                                                Color(0xFF4CAF50)
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.15f
                                                                                        )
                                                                        )
                                                                        .border(
                                                                                1.dp,
                                                                                Color(0xFF4CAF50)
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.5f
                                                                                        ),
                                                                                RoundedCornerShape(
                                                                                        16.dp
                                                                                )
                                                                        )
                                                                        .clickable {
                                                                                BottomBarState
                                                                                        .isDeleteModeEnabled =
                                                                                        false
                                                                        }
                                                                        .padding(
                                                                                horizontal = 12.dp,
                                                                                vertical = 6.dp
                                                                        ),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        Row(
                                                                verticalAlignment =
                                                                        Alignment.CenterVertically,
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(6.dp)
                                                        ) {
                                                                Icon(
                                                                        imageVector =
                                                                                Icons.Default.Check,
                                                                        contentDescription = null,
                                                                        tint = Color(0xFF4CAF50),
                                                                        modifier =
                                                                                Modifier.size(14.dp)
                                                                )
                                                                Text(
                                                                        text = "CONCLUIR",
                                                                        color = Color(0xFF4CAF50),
                                                                        fontSize = 11.sp,
                                                                        fontWeight =
                                                                                FontWeight
                                                                                        .ExtraBold,
                                                                        letterSpacing = 0.5.sp
                                                                )
                                                        }
                                                }
                                        }
                                }
                                IconButton(
                                        onClick = {
                                                BottomBarState.isMenuExpanded = false
                                                BottomBarState.isDeleteModeEnabled = false
                                        },
                                        modifier = Modifier.size(24.dp)
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Fechar",
                                                tint = Color.White.copy(alpha = 0.7f)
                                        )
                                }
                        }

                        // App Grid
                        Column(
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier =
                                        Modifier.onGloballyPositioned { containerCoordinates = it }
                                                .pointerInput(BottomBarState.isDeleteModeEnabled) {
                                                        if (BottomBarState.isDeleteModeEnabled) {
                                                                detectDragGestures(
                                                                        onDragStart = { offset ->
                                                                                val index =
                                                                                        findIndexAtOffset(
                                                                                                offset
                                                                                        )
                                                                                if (index != null &&
                                                                                                index <
                                                                                                        configsList
                                                                                                                .size
                                                                                ) {
                                                                                        draggedIndex =
                                                                                                index
                                                                                        dragOffset =
                                                                                                Offset.Zero
                                                                                        Log.d(
                                                                                                "AppMenuContent",
                                                                                                "Drag started via container for index $index"
                                                                                        )
                                                                                }
                                                                        },
                                                                        onDrag = {
                                                                                change,
                                                                                dragAmount ->
                                                                                if (draggedIndex !=
                                                                                                null
                                                                                ) {
                                                                                        try {
                                                                                                // Support multiple Compose versions
                                                                                                // via reflection if needed,
                                                                                                // but on container we usually don't
                                                                                                // need to consume for children
                                                                                                change.consume()
                                                                                        } catch (
                                                                                                e:
                                                                                                        Exception) {}

                                                                                        dragOffset +=
                                                                                                dragAmount

                                                                                        // Check for
                                                                                        // swaps
                                                                                        val currentItemBounds =
                                                                                                itemBounds[
                                                                                                        draggedIndex!!]
                                                                                        if (currentItemBounds !=
                                                                                                        null
                                                                                        ) {
                                                                                                val currentPos =
                                                                                                        currentItemBounds
                                                                                                                .center +
                                                                                                                dragOffset
                                                                                                itemBounds
                                                                                                        .entries
                                                                                                        .forEach {
                                                                                                                entry
                                                                                                                ->
                                                                                                                val targetIndex =
                                                                                                                        entry.key
                                                                                                                val bounds =
                                                                                                                        entry.value

                                                                                                                val hitZone =
                                                                                                                        Rect(
                                                                                                                                left =
                                                                                                                                        bounds.left +
                                                                                                                                                bounds.width *
                                                                                                                                                        0.2f,
                                                                                                                                top =
                                                                                                                                        bounds.top +
                                                                                                                                                bounds.height *
                                                                                                                                                        0.2f,
                                                                                                                                right =
                                                                                                                                        bounds.right -
                                                                                                                                                bounds.width *
                                                                                                                                                        0.2f,
                                                                                                                                bottom =
                                                                                                                                        bounds.bottom -
                                                                                                                                                bounds.height *
                                                                                                                                                        0.2f
                                                                                                                        )

                                                                                                                if (targetIndex !=
                                                                                                                                draggedIndex &&
                                                                                                                                targetIndex <
                                                                                                                                        configsList
                                                                                                                                                .size &&
                                                                                                                                hitZone.contains(
                                                                                                                                        currentPos
                                                                                                                                )
                                                                                                                ) {
                                                                                                                        val temp =
                                                                                                                                configsList[
                                                                                                                                        draggedIndex!!]
                                                                                                                        configsList[
                                                                                                                                draggedIndex!!] =
                                                                                                                                configsList[
                                                                                                                                        targetIndex]
                                                                                                                        configsList[
                                                                                                                                targetIndex] =
                                                                                                                                temp

                                                                                                                        draggedIndex =
                                                                                                                                targetIndex
                                                                                                                        dragOffset =
                                                                                                                                Offset.Zero
                                                                                                                        br.com
                                                                                                                                .redesurftank
                                                                                                                                .havalshisuku
                                                                                                                                .managers
                                                                                                                                .DisplayAppLauncher
                                                                                                                                .saveAllConfigs(
                                                                                                                                        configsList
                                                                                                                                                .toList()
                                                                                                                                )
                                                                                                                }
                                                                                                        }
                                                                                        }
                                                                                }
                                                                        },
                                                                        onDragEnd = {
                                                                                draggedIndex = null
                                                                                dragOffset =
                                                                                        Offset.Zero
                                                                        },
                                                                        onDragCancel = {
                                                                                draggedIndex = null
                                                                                dragOffset =
                                                                                        Offset.Zero
                                                                        }
                                                                )
                                                        }
                                                }
                        ) {
                                for (r in (rows - 1) downTo 0) {
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                for (c in 0 until columns) {
                                                        val index = r * columns + c
                                                        if (index < totalItems) {
                                                                if (showAddButton &&
                                                                                index ==
                                                                                        totalItems -
                                                                                                1
                                                                ) {
                                                                        Box(
                                                                                modifier =
                                                                                        Modifier.weight(
                                                                                                1f
                                                                                        ),
                                                                                contentAlignment =
                                                                                        Alignment
                                                                                                .Center
                                                                        ) {
                                                                                AddAppGridItem(
                                                                                        currentPkg,
                                                                                        context,
                                                                                        scope
                                                                                ) {
                                                                                        val newConfigs =
                                                                                                br.com
                                                                                                        .redesurftank
                                                                                                        .havalshisuku
                                                                                                        .managers
                                                                                                        .DisplayAppLauncher
                                                                                                        .getAllConfigs()
                                                                                        configsList
                                                                                                .clear()
                                                                                        configsList
                                                                                                .addAll(
                                                                                                        newConfigs
                                                                                                )
                                                                                }
                                                                        }
                                                                } else {
                                                                        val config =
                                                                                configsList[index]
                                                                        key(config.packageName) {
                                                                                Box(
                                                                                        modifier =
                                                                                                Modifier.weight(
                                                                                                                1f
                                                                                                        )
                                                                                                        .onGloballyPositioned {
                                                                                                                layoutCoordinates
                                                                                                                ->
                                                                                                                itemBounds[
                                                                                                                        index] =
                                                                                                                        layoutCoordinates
                                                                                                                                .boundsInRoot()
                                                                                                        },
                                                                                        contentAlignment =
                                                                                                Alignment
                                                                                                        .Center
                                                                                ) {
                                                                                        AppGridItem(
                                                                                                config.packageName,
                                                                                                config.substituteIcon,
                                                                                                context,
                                                                                                scope,
                                                                                                onDelete = {
                                                                                                        configsList
                                                                                                                .removeAt(
                                                                                                                        index
                                                                                                                )
                                                                                                        br.com
                                                                                                                .redesurftank
                                                                                                                .havalshisuku
                                                                                                                .managers
                                                                                                                .DisplayAppLauncher
                                                                                                                .saveAllConfigs(
                                                                                                                        configsList
                                                                                                                                .toList()
                                                                                                                )
                                                                                                },
                                                                                                onDragStart = {
                                                                                                },
                                                                                                onDrag = {
                                                                                                        _
                                                                                                        ->
                                                                                                },
                                                                                                onDragEnd = {
                                                                                                },
                                                                                                isDragged =
                                                                                                        (draggedIndex ==
                                                                                                                index),
                                                                                                dragOffset =
                                                                                                        if (draggedIndex ==
                                                                                                                        index
                                                                                                        )
                                                                                                                dragOffset
                                                                                                        else
                                                                                                                Offset.Zero
                                                                                        ) {
                                                                                                BottomBarState
                                                                                                        .isMenuExpanded =
                                                                                                        false
                                                                                        }
                                                                                }
                                                                        }
                                                                }
                                                        } else {
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.weight(1f)
                                                                )
                                                        }
                                                }
                                        }
                                }
                        }
                }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddAppGridItem(pkg: String, context: Context, scope: CoroutineScope, onAdded: () -> Unit) {
        val appInfo =
                remember(pkg) {
                        br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.resolveAppInfo(
                                context,
                                pkg
                        )
                }

        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .clickable {
                                        scope.launch {
                                                br.com.redesurftank.havalshisuku.managers
                                                        .DisplayAppLauncher
                                                        .getOrCreateDefaultConfig(context, pkg)
                                                onAdded()
                                        }
                                }
                                .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Box(
                        modifier =
                                Modifier.size(64.dp)
                                        .background(
                                                Color.White.copy(alpha = 0.05f),
                                                RoundedCornerShape(12.dp)
                                        )
                                        .border(
                                                1.dp,
                                                Color.White.copy(alpha = 0.2f),
                                                RoundedCornerShape(12.dp)
                                        ),
                        contentAlignment = Alignment.Center
                ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(24.dp)
                                )
                                if (appInfo.icon != null) {
                                        AsyncImage(
                                                model = appInfo.icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp).alpha(0.4f)
                                        )
                                }
                        }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                        text = "Adicionar",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
        }
}

@Composable
fun CarSettingsSection() {
        val showSettings = BottomBarState.isSettingsMenuExpanded
        Box(
                modifier =
                        Modifier.fillMaxHeight().width(40.dp).clickable {
                                BottomBarState.isSettingsMenuExpanded = !showSettings
                        },
                contentAlignment = Alignment.Center
        ) {
                Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(
                                imageVector = Icons.Default.DirectionsCar,
                                contentDescription = "Configurações do Carro",
                                tint = if (showSettings) Color(0xFF2196F3) else Color.White,
                                modifier = Modifier.size(32.dp)
                        )
                        // Smaller gear icon overlay
                        Box(
                                modifier =
                                        Modifier.size(16.dp)
                                                .offset(x = 10.dp, y = 10.dp)
                                                .background(Color.Black, RoundedCornerShape(4.dp))
                                                .padding(1.dp),
                                contentAlignment = Alignment.Center
                        ) {
                                Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = if (showSettings) Color(0xFF2196F3) else Color.White,
                                        modifier = Modifier.size(14.dp)
                                )
                        }
                }
        }
}

@Composable
fun SettingsMenuContent(drive: String, ev: String, regen: String, steer: String) {
        val serviceManager = br.com.redesurftank.havalshisuku.managers.ServiceManager.getInstance()
        Box(
                modifier =
                        Modifier.background(
                                        Color(0xFF13151A).copy(alpha = 0.95f),
                                        RoundedCornerShape(12.dp)
                                )
                                .border(1.dp, Color(0xFF1D2430), RoundedCornerShape(12.dp))
                                .width(480.dp)
                                .padding(16.dp)
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        // Category: Drive Mode
                        SettingsCategoryRow(
                                "Modo de Condução",
                                drive,
                                listOf(
                                        "2" to "Eco",
                                        "0" to "Normal",
                                        "1" to "Sport",
                                        "3" to "Neve",
                                        "4" to "Areia",
                                        "5" to "Lama"
                                ),
                                columns = 3
                        ) { newVal ->
                                serviceManager.updateData(
                                        CarConstants.CAR_DRIVE_SETTING_DRIVE_MODE.getValue(),
                                        newVal
                                )
                        }

                        // Category: EV Mode
                        SettingsCategoryRow(
                                "Modo EV",
                                ev,
                                listOf("0" to "HEV", "1" to "EV Prioritário", "3" to "EV")
                        ) { newVal ->
                                serviceManager.updateData(
                                        CarConstants.CAR_EV_SETTING_POWER_MODEL_CONFIG.getValue(),
                                        newVal
                                )
                        }

                        // Category: Regen
                        SettingsCategoryRow(
                                "Modo de Regeneração",
                                regen,
                                listOf("2" to "Baixo", "0" to "Normal", "1" to "Alto")
                        ) { newVal ->
                                serviceManager.updateData(
                                        CarConstants.CAR_EV_SETTING_ENERGY_RECOVERY_LEVEL
                                                .getValue(),
                                        newVal
                                )
                        }

                        // Category: Steering
                        SettingsCategoryRow(
                                "Modo de Direção",
                                steer,
                                listOf("2" to "Conforto", "0" to "Normal", "1" to "Esportiva")
                        ) { newVal ->
                                serviceManager.updateData(
                                        CarConstants.CAR_DRIVE_SETTING_STEERING_WHEEL_ASSIST_MODE
                                                .getValue(),
                                        newVal
                                )
                        }

                        Box(
                                modifier =
                                        Modifier.fillMaxWidth().height(24.dp).clickable {
                                                br.com.redesurftank.havalshisuku.models
                                                        .BottomBarState.isSettingsMenuExpanded =
                                                        false
                                        },
                                contentAlignment = Alignment.Center
                        ) {
                                Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Fechar",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp)
                                )
                        }
                }
        }
}

@Composable
fun BottomBarMenus() {
        val serviceManager = br.com.redesurftank.havalshisuku.managers.ServiceManager.getInstance()

        var driveMode by remember {
                mutableStateOf(
                        serviceManager.getData(CarConstants.CAR_DRIVE_SETTING_DRIVE_MODE.getValue())
                                ?: "0"
                )
        }
        var powerModel by remember {
                mutableStateOf(
                        serviceManager.getData(
                                CarConstants.CAR_EV_SETTING_POWER_MODEL_CONFIG.getValue()
                        )
                                ?: "0"
                )
        }
        var energyRecovery by remember {
                mutableStateOf(
                        serviceManager.getData(
                                CarConstants.CAR_EV_SETTING_ENERGY_RECOVERY_LEVEL.getValue()
                        )
                                ?: "0"
                )
        }
        var steeringMode by remember {
                mutableStateOf(
                        serviceManager.getData(
                                CarConstants.CAR_DRIVE_SETTING_STEERING_WHEEL_ASSIST_MODE.getValue()
                        )
                                ?: "0"
                )
        }

        // Update states when data changes
        DisposableEffect(Unit) {
                val listener =
                        object : br.com.redesurftank.havalshisuku.listeners.IDataChanged {
                                override fun onDataChanged(key: String, value: String?) {
                                        if (value == null) return
                                        when (key) {
                                                CarConstants.CAR_EV_SETTING_POWER_MODEL_CONFIG
                                                        .getValue() -> powerModel = value
                                                CarConstants.CAR_EV_SETTING_ENERGY_RECOVERY_LEVEL
                                                        .getValue() -> energyRecovery = value
                                                CarConstants.CAR_DRIVE_SETTING_DRIVE_MODE
                                                        .getValue() -> driveMode = value
                                                CarConstants
                                                        .CAR_DRIVE_SETTING_STEERING_WHEEL_ASSIST_MODE
                                                        .getValue() -> steeringMode = value
                                        }
                                }
                        }
                serviceManager.addDataChangedListener(listener)
                onDispose { serviceManager.removeDataChangedListener(listener) }
        }

        Box(
                modifier =
                        Modifier.fillMaxSize()
                                .background(
                                        Color.Black.copy(alpha = 0.4f)
                                ) // Semi-transparent black overlay
                                .clickable(
                                        interactionSource =
                                                remember {
                                                        androidx.compose.foundation.interaction
                                                                .MutableInteractionSource()
                                                },
                                        indication = null
                                ) {
                                        BottomBarState.isMenuExpanded = false
                                        BottomBarState.isSettingsMenuExpanded = false
                                        BottomBarState.isOverrideMenuExpanded = false
                                        BottomBarState.isDeleteModeEnabled = false
                                },
                contentAlignment = Alignment.BottomCenter
        ) {
                // We use a Box with fillMaxWidth to contain our menus at the bottom
                Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp),
                ) {
                        // App Menu (Left side)
                        if (br.com.redesurftank.havalshisuku.models.BottomBarState.isMenuExpanded) {
                                Box(
                                        modifier =
                                                Modifier.padding(start = 16.dp)
                                                        .align(Alignment.BottomStart)
                                                        .clickable(enabled = false) {}
                                ) { AppMenuContent() }
                        }

                        // Settings/Override Menu
                        if (BottomBarState.isSettingsMenuExpanded ||
                                        BottomBarState.isOverrideMenuExpanded
                        ) {
                                Box(
                                        modifier =
                                                Modifier.align(
                                                                if (BottomBarState
                                                                                .isSettingsMenuExpanded
                                                                )
                                                                        Alignment.BottomStart
                                                                else Alignment.BottomEnd
                                                        )
                                                        .padding(horizontal = 16.dp)
                                                        .clickable(enabled = false) {}
                                ) {
                                        if (BottomBarState.isSettingsMenuExpanded) {
                                                SettingsMenuContent(
                                                        driveMode,
                                                        powerModel,
                                                        energyRecovery,
                                                        steeringMode
                                                )
                                        } else if (BottomBarState.isOverrideMenuExpanded) {
                                                OverrideMenuContent()
                                        }
                                }
                        }
                }
        }
}

@Composable
fun SettingsCategoryRow(
        label: String,
        currentValue: String,
        options: List<Pair<String, String>>,
        columns: Int = 0,
        onSelect: (String) -> Unit
) {
        Column {
                Text(text = label, style = labelStyle.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                if (columns > 0) {
                        val rows = (options.size + columns - 1) / columns
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (r in 0 until rows) {
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                for (c in 0 until columns) {
                                                        val index = r * columns + c
                                                        if (index < options.size) {
                                                                val (valKey, valLabel) =
                                                                        options[index]
                                                                val isSelected =
                                                                        currentValue == valKey
                                                                Surface(
                                                                        onClick = {
                                                                                onSelect(valKey)
                                                                        },
                                                                        modifier =
                                                                                Modifier.weight(1f),
                                                                        color =
                                                                                if (isSelected)
                                                                                        Color(
                                                                                                        0xFF2196F3
                                                                                                )
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.2f
                                                                                                )
                                                                                else
                                                                                        Color.White
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.05f
                                                                                                ),
                                                                        shape =
                                                                                RoundedCornerShape(
                                                                                        8.dp
                                                                                ),
                                                                        border =
                                                                                BorderStroke(
                                                                                        width =
                                                                                                1.dp,
                                                                                        color =
                                                                                                if (isSelected
                                                                                                )
                                                                                                        Color(
                                                                                                                0xFF2196F3
                                                                                                        )
                                                                                                else
                                                                                                        Color.Transparent
                                                                                )
                                                                ) {
                                                                        Text(
                                                                                text = valLabel,
                                                                                color =
                                                                                        if (isSelected
                                                                                        )
                                                                                                Color(
                                                                                                        0xFF2196F3
                                                                                                )
                                                                                        else
                                                                                                Color.White,
                                                                                fontSize = 12.sp,
                                                                                fontWeight =
                                                                                        if (isSelected
                                                                                        )
                                                                                                FontWeight
                                                                                                        .Bold
                                                                                        else
                                                                                                FontWeight
                                                                                                        .Normal,
                                                                                fontFamily =
                                                                                        Michroma,
                                                                                textAlign =
                                                                                        TextAlign
                                                                                                .Center,
                                                                                modifier =
                                                                                        Modifier.padding(
                                                                                                vertical =
                                                                                                        16.dp
                                                                                        )
                                                                        )
                                                                }
                                                        } else {
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.weight(1f)
                                                                )
                                                        }
                                                }
                                        }
                                }
                        }
                } else {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                                options.forEach { (valKey, valLabel) ->
                                        val isSelected = currentValue == valKey
                                        Surface(
                                                onClick = { onSelect(valKey) },
                                                modifier = Modifier.weight(1f),
                                                color =
                                                        if (isSelected)
                                                                Color(0xFF2196F3).copy(alpha = 0.2f)
                                                        else Color.White.copy(alpha = 0.05f),
                                                shape = RoundedCornerShape(8.dp),
                                                border =
                                                        BorderStroke(
                                                                width = 1.dp,
                                                                color =
                                                                        if (isSelected)
                                                                                Color(0xFF2196F3)
                                                                        else Color.Transparent
                                                        )
                                        ) {
                                                Text(
                                                        text = valLabel,
                                                        color =
                                                                if (isSelected) Color(0xFF2196F3)
                                                                else Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight =
                                                                if (isSelected) FontWeight.Bold
                                                                else FontWeight.Normal,
                                                        fontFamily = Michroma,
                                                        textAlign = TextAlign.Center,
                                                        modifier =
                                                                Modifier.padding(vertical = 16.dp)
                                                )
                                        }
                                }
                        }
                }
        }
}

@Composable
fun TempControlSection(
        label: String,
        temp: String,
        isEnabled: Boolean,
        onValueChange: (Float) -> Unit
) {
        val alpha = if (isEnabled) 1f else 0.4f
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(alpha)) {
                SmallButton(Icons.Default.Remove, isEnabled) { onValueChange(-0.5f) }
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(120.dp)
                ) {
                        Text(text = label, style = labelStyle)
                        val floatTemp = temp.toFloatOrNull() ?: -200f
                        val isAbnormal = floatTemp >= 85f || floatTemp <= -40f || floatTemp == -1f
                        val displayTemp = if (!isEnabled || isAbnormal) "--" else temp
                        val tempColor = if (floatTemp > 30f) Color.Red else Color.White
                        Text(
                                text =
                                        buildAnnotatedString {
                                                withStyle(style = SpanStyle(color = tempColor)) {
                                                        append(displayTemp)
                                                }
                                                if (displayTemp != "--") append("°C")
                                        },
                                style = commonTextStyle.copy(fontSize = 18.sp),
                                modifier = Modifier.padding(horizontal = 4.dp)
                        )
                }
                SmallButton(Icons.Default.Add, isEnabled) { onValueChange(0.5f) }
        }
}

@Composable
fun FanControlSection(speed: Int, isEnabled: Boolean, onValueChange: (Int) -> Unit) {
        val alpha = if (isEnabled) 1f else 0.4f
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(alpha)) {
                SmallButton(Icons.Default.Remove, isEnabled) { onValueChange(-1) }
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(120.dp)
                ) {
                        Text(text = "Ventilação", style = labelStyle.copy(fontSize = 10.sp))
                        Text(
                                text = speed.toString(),
                                style = commonTextStyle.copy(fontSize = 18.sp)
                        )
                }
                SmallButton(Icons.Default.Add, isEnabled) { onValueChange(1) }
        }
}

@Composable
fun FanSpeedIcon(speed: Int) {
        Canvas(modifier = Modifier.size(24.dp).padding(2.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2f
                val innerRadius = radius * 0.3f

                // Draw 7 segments around the circle
                val segmentGap = 10f
                val totalGap = segmentGap * 7
                val sweepAngle = (360f - totalGap) / 7f

                for (i in 0 until 7) {
                        val startAngle = i * (sweepAngle + segmentGap) - 90f
                        val isActive = i < speed
                        val color = if (isActive) Color.White else Color.Gray.copy(alpha = 0.3f)

                        drawArc(
                                color = color,
                                startAngle = startAngle,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                }

                // Draw a small fan hub in the center
                drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = innerRadius,
                        center = center
                )
        }
}

@Composable
fun VolumeControlSection(label: String, volume: Int, onValueChange: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton(Icons.Default.Remove) { onValueChange(-1) }
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(60.dp)
                ) {
                        Text(text = label, style = labelStyle)
                        Text(
                                text = volume.toString(),
                                style = commonTextStyle,
                                modifier = Modifier.padding(horizontal = 4.dp)
                        )
                }
                SmallButton(Icons.Default.Add) { onValueChange(1) }
        }
}

@Composable
fun ControlsSection(scope: CoroutineScope) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                                Modifier.clickable {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                ShizukuUtils.runCommandAndGetOutput(
                                                        arrayOf("input", "keyevent", "4")
                                                )
                                        }
                                }
                ) {
                        Text(
                                text = "Voltar",
                                style = labelStyle.copy(fontSize = 10.sp, color = Color.White)
                        )
                        Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                        )
                }

                val showSettings = BottomBarState.isSettingsMenuExpanded
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                                Modifier.clickable {
                                        BottomBarState.isSettingsMenuExpanded = !showSettings
                                }
                ) {
                        Text(
                                text = "Condução",
                                style =
                                        labelStyle.copy(
                                                fontSize = 10.sp,
                                                color =
                                                        if (showSettings) Color(0xFF2196F3)
                                                        else Color.White
                                        )
                        )
                        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                        imageVector = Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        tint = if (showSettings) Color(0xFF2196F3) else Color.White,
                                        modifier = Modifier.size(20.dp)
                                )
                                Box(
                                        modifier =
                                                Modifier.size(10.dp)
                                                        .offset(x = 6.dp, y = 6.dp)
                                                        .background(
                                                                Color.Black,
                                                                RoundedCornerShape(2.dp)
                                                        )
                                                        .padding(0.5.dp),
                                        contentAlignment = Alignment.Center
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                tint =
                                                        if (showSettings) Color(0xFF2196F3)
                                                        else Color.White,
                                                modifier = Modifier.size(8.dp)
                                        )
                                }
                        }
                }
        }
}

@Composable
fun NavIcon(icon: ImageVector, onClick: () -> Unit) {
        IconButton(onClick = onClick) {
                Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
        }
}

@Composable
fun ACControlButton(
        icon: ImageVector,
        label: String,
        isActive: Boolean,
        isEnabled: Boolean,
        onClick: () -> Unit
) {
        val context = LocalContext.current
        val alpha = if (isEnabled) 1f else 0.4f
        val activeColor = Color(0xFF2196F3) // Vibrant blue for active state
        val contentColor = if (isActive && isEnabled) activeColor else Color.White

        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(alpha).clickable(enabled = isEnabled) { onClick() }
        ) {
                Text(
                        text = label,
                        style =
                                labelStyle.copy(
                                        fontSize = 10.sp,
                                        color = contentColor,
                                        fontWeight =
                                                if (isActive) FontWeight.Bold else FontWeight.Medium
                                )
                )
                Icon(
                        icon,
                        contentDescription = label,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                )
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmallButton(
        icon: ImageVector,
        enabled: Boolean = true,
        iconSize: androidx.compose.ui.unit.Dp = 20.dp,
        onClick: () -> Unit
) {
        Surface(
                onClick = if (enabled) onClick else ({}),
                shape = RoundedCornerShape(0.dp),
                color = Color.Black.copy(alpha = 0.95f),
                modifier = Modifier.size(70.dp, 60.dp)
        ) {
                Box(contentAlignment = Alignment.Center) {
                        Icon(
                                icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(iconSize)
                        )
                }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGridItem(
        pkg: String,
        substituteIcon: String?,
        context: Context,
        scope: CoroutineScope,
        onDelete: () -> Unit,
        onDragStart: () -> Unit,
        onDrag: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        isDragged: Boolean,
        dragOffset: Offset,
        onClick: () -> Unit
) {
        val appConfig =
                remember(pkg) {
                        br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.getAppConfig(
                                pkg
                        )
                }
        val appInfo =
                remember(pkg) {
                        br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.resolveAppInfo(
                                context,
                                pkg,
                                appConfig?.customName
                        )
                }

        val substituteIconVector = getSubstituteIconVector(substituteIcon)

        val iconTint = appConfig?.iconColor.toComposeColor()
        val displayName = appInfo.label
        val isDeleteMode = BottomBarState.isDeleteModeEnabled

        // Cached reflection for gesture consumption
        val consumeMethod = remember {
                try {
                        PointerInputChange::class.java.methods.find {
                                it.name == "consume" || it.name == "consumeAllChanges"
                        }
                } catch (e: Exception) {
                        null
                }
        }

        // Shaking animation for delete mode
        val infiniteTransition = rememberInfiniteTransition(label = "shake")
        val rotation by
                infiniteTransition.animateFloat(
                        initialValue = -1.5f,
                        targetValue = 1.5f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation = tween(120, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                ),
                        label = "rotation"
                )

        val baseModifier = Modifier
                .fillMaxWidth()
                .zIndex(if (isDragged) 1f else 0f)

        val itemModifier = if (isDeleteMode || isDragged) {
                baseModifier.graphicsLayer {
                        rotationZ = if (isDeleteMode && !isDragged) rotation else 0f
                        translationX = dragOffset.x
                        translationY = dragOffset.y
                        scaleX = 1f
                        scaleY = 1f
                        alpha = if (isDragged) 0.8f else 1f
                }
        } else {
                baseModifier
        }

        Column(
                modifier =
                        itemModifier
                                .pointerInput(isDeleteMode) {
                                        // Container handles drag logic now
                                }
                                .combinedClickable(
                                        onClick = {
                                                if (isDeleteMode) {
                                                        BottomBarState.isDeleteModeEnabled = false
                                                } else {
                                                        onClick()
                                                        // Update shared selection state
                                                        BottomBarState.selectedPackage = pkg
                                                        // Launch immediately on selection
                                                        scope.launch {
                                                                br.com.redesurftank.havalshisuku
                                                                        .managers.DisplayAppLauncher
                                                                        .launchAnyApp(context, pkg)
                                                        }
                                                }
                                        },
                                        onLongClick = { BottomBarState.isDeleteModeEnabled = true }
                                )
                                .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        Box(
                                modifier =
                                        Modifier.size(60.dp)
                                                .background(
                                                        Color.White.copy(alpha = 0.1f),
                                                        RoundedCornerShape(12.dp)
                                                ),
                                contentAlignment = Alignment.Center
                        ) {
                                if (substituteIcon == "youtube" ||
                                                substituteIcon == "youtube_music" ||
                                                substituteIcon == "gwm"
                                ) {
                                        Image(
                                                painter =
                                                        painterResource(
                                                                id =
                                                                        when (substituteIcon) {
                                                                                "youtube" ->
                                                                                        R.drawable
                                                                                                .ic_youtube_default
                                                                                "youtube_music" ->
                                                                                        R.drawable
                                                                                                .ic_youtube_music_default
                                                                                "gwm" ->
                                                                                        R.drawable
                                                                                                .ic_gwm
                                                                                else ->
                                                                                        R.drawable
                                                                                                .ic_youtube_default
                                                                        }
                                                        ),
                                                contentDescription = null,
                                                modifier = Modifier.size(40.dp)
                                        )
                                } else if (substituteIconVector != null) {
                                        Icon(
                                                substituteIconVector,
                                                contentDescription = null,
                                                tint = iconTint,
                                                modifier = Modifier.size(40.dp)
                                        )
                                } else if (appInfo.icon != null) {
                                        AsyncImage(
                                                model = appInfo.icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp)
                                        )
                                }
                        }

                        if (isDeleteMode) {
                                Box(
                                        modifier =
                                                Modifier.size(24.dp)
                                                        .align(Alignment.TopEnd)
                                                        .background(Color.Red, CircleShape)
                                                        .clickable {
                                                                scope.launch {
                                                                        br.com.redesurftank
                                                                                .havalshisuku
                                                                                .managers
                                                                                .DisplayAppLauncher
                                                                                .deleteConfig(pkg)
                                                                        onDelete()
                                                                }
                                                        },
                                        contentAlignment = Alignment.Center
                                ) {
                                        Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remover",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                        )
                                }
                        }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                        text = displayName,
                        color = Color.White,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                )
        }
}

@Composable
fun OverrideMenuContent() {
        val context = LocalContext.current
        val pkg = BottomBarState.currentPackage
        val prefs = remember {
                br.com.redesurftank.App.getDeviceProtectedContext()
                        .getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)
        }

        // Load current app settings from SharedPreferences
        val overridesJson = prefs.getString(SharedPreferencesKeys.BOTTOM_BAR_OVERRIDES.key, null)
        val gson = com.google.gson.Gson()
        val type =
                object :
                                com.google.gson.reflect.TypeToken<
                                        MutableMap<String, Map<String, Int>>>() {}
                        .type
        val overrides: MutableMap<String, Map<String, Int>> =
                if (overridesJson != null) {
                        try {
                                gson.fromJson(overridesJson, type)
                        } catch (e: Exception) {
                                mutableMapOf()
                        }
                } else {
                        mutableMapOf()
                }

        val currentSettings = overrides[pkg]
        val overscanValues = listOf(0, 15, 20, 30, 45, 60, 75, 90, 105, 120)
        val currentOverscan = currentSettings?.get("overscan") ?: 20
        var overscanIndex by
                remember(pkg) {
                        mutableIntStateOf(overscanValues.indexOf(currentOverscan).coerceAtLeast(0))
                }
        var offset by remember(pkg) { mutableIntStateOf(currentSettings?.get("offset") ?: 0) }

        // Helper to auto-apply and save
        val updateSettings = { newOverscan: Int, newOffset: Int ->
                val density = context.resources.displayMetrics.density
                val overscanPx = (newOverscan * density).toInt()

                // Apply immediately
                br.com.redesurftank.havalshisuku.utils.ShizukuUtils.runCommandAndGetOutput(
                        arrayOf("wm", "overscan", "0,0,0,$overscanPx")
                )
                context.sendBroadcast(
                        android.content.Intent(
                                        "br.com.redesurftank.havalshisuku.UPDATE_BAR_POSITION"
                                )
                                .apply {
                                        setPackage(context.packageName)
                                        putExtra("overscan", newOverscan)
                                        putExtra("offset", newOffset)
                                }
                )

                // Save to preferences
                val newOverrides = overrides.toMutableMap()
                newOverrides[pkg] = mapOf("overscan" to newOverscan, "offset" to newOffset)
                prefs.edit()
                        .putString(
                                SharedPreferencesKeys.BOTTOM_BAR_OVERRIDES.key,
                                gson.toJson(newOverrides)
                        )
                        .apply()
        }

        Box(
                modifier =
                        Modifier.background(
                                        Color(0xFF13151A).copy(alpha = 0.95f),
                                        RoundedCornerShape(12.dp)
                                )
                                .border(1.dp, Color(0xFF1D2430), RoundedCornerShape(12.dp))
                                .width(360.dp)
                                .padding(16.dp)
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                                text = "Ajuste Real-time: $pkg",
                                style =
                                        labelStyle.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                        )
                        )

                        OverrideControlRow(
                                "Overscan (Value: ${overscanValues[overscanIndex]})",
                                overscanIndex,
                                0..9,
                                steps = 8
                        ) {
                                overscanIndex = it
                                updateSettings(overscanValues[it], offset)
                        }

                        OverrideControlRow(
                                "Offset (Move a barra para baixo)",
                                offset,
                                -150..150,
                                steps = 59
                        ) {
                                offset = it
                                updateSettings(overscanValues[overscanIndex], it)
                        }

                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                                Button(
                                        onClick = { BottomBarState.isOverrideMenuExpanded = false },
                                        modifier = Modifier.weight(1f),
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF2196F3)
                                                )
                                ) { Text("Fechar", color = Color.White) }

                                Button(
                                        onClick = {
                                                // Reset everything
                                                overscanIndex = overscanValues.indexOf(20).coerceAtLeast(0)
                                                offset = 0
                                                updateSettings(20, 0)

                                                val newOverrides = overrides.toMutableMap()
                                                newOverrides.remove(pkg)
                                                prefs.edit()
                                                        .putString(
                                                                SharedPreferencesKeys
                                                                        .BOTTOM_BAR_OVERRIDES
                                                                        .key,
                                                                gson.toJson(newOverrides)
                                                        )
                                                        .apply()

                                                BottomBarState.isOverrideMenuExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = Color.DarkGray
                                                )
                                ) { Text("Resetar", color = Color.White) }
                        }
                }
        }
}

@Composable
fun OverrideControlRow(
        label: String,
        value: Int,
        range: IntRange,
        steps: Int = 0,
        onValueChange: (Int) -> Unit
) {
        Column {
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                        Text(text = label, style = labelStyle)
                        Text(
                                text = value.toString(),
                                style = commonTextStyle.copy(fontSize = 14.sp)
                        )
                }
                Slider(
                        value = value.toFloat(),
                        onValueChange = { onValueChange(it.toInt()) },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = steps,
                        colors =
                                SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White
                                )
                )
        }
}
