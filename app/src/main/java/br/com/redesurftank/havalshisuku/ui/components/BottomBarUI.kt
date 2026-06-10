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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
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
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
private const val recycleIn = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADwAAAA8CAYAAAA6/NlyAAAJDElEQVR4AcTau5JcVxUG4NOjsUYSJVOYi7kURGROCKGIKV4AZbwAMVSR+gEg5gXIzAtQxBSEJM6IoLiYiymsQjePaM63p//26j379HTPjIRq/l5rr/u/9z6nNbJPptv5s5rLrKb1+kJO06J89Gh957YwTct9pmnj+2SmyZ+bEe6KTSs8ld3FTPAk2PXcbJWakcNqZlqvp3YY6/XquoRzmsMejBmCtF7CO+9Md47FUi29gp0YpDc4nvC8S62YAk3Z/Rg23ISMiG1cR4m+zih5aY7jCCOL6Hq+IrrQyRlLDepwc9gr+dnXo5/rGMKr7TNaiGKgKFmRIaptqH9+utvs5Aic7OQBWOprRjiE8GruA7PY/VEAdq3TpGlv264zPAkckX+fXlg2RI+PDFrA/GE9i9GPGaD37SOMJPQ506NH6/bWrQ7Fg2pvegbrZXOWj/iZqm5dwQdsvWQr6GeqhJGrKGkX6j6iFxHdp2GAOZK+gOf3p/tckfSDkNokDJJCHOGQHIRN06PNaZIJSDIZW5NpRkIzXv4IoV6ePZ2eskXSK1SyJvdCbxgEIXzJjFxQnQhCtV3SFxqJMyyEUGyRfNFJcSTQ47euuvUQg1l2CI9IKoQk0IdQGAbODEYaWgidvAo1rtfVYgsWa5kLNgGN8LWIKhJsilVhkKyjR8Z+E1lrVfKLNc06Oy+9bWdb+1pZPFGJkK8NCQV1kKqXkEV1fTY95Owl2z7UPtEjd/LmudsJx4gkZH1JzglbW9U3xjSJtPMb11Yg88HjJ+/+6V8f/fIPHz7+oOKPf3n8e2uS/89//egXJMgJ/vnsxXfU2RYtip76k8W8VRthJGFrrQpiQbVvdMWpkbVRbPwGNDAyzz9++YOX69U32JfAXyEn+M+T5z9Xx0YgX2ukJwnVRz/ZS1TEHiiIIJmwqrMhaigDGpjttmBDkB8RT49unqmdcJxNOs2mzB9Vn5d+UoDsyfJXIPu3fzz5oaGq/c5q/btPPTj7/le/9PDr+yCm4uyNOz8D+bXePuKZ0bxyLhNmHRBlhhSgpwi9B7KewXqqBkXgy19883tvffrub/ucfi2m4gufe/BTkG+jkK85lbj+fJnR3NYXhEMwkqcgSSQkuYRsVY0AWQPEgaxBEYjtphL5JeIeIe+M9DA3/YIwbQ9ZBCWQQulkD0TZerJOAlm+V4G3Hz54d0Tc7fJ8Zy5zn0x7iPbDSehtdf3hv198087Wk3WFnUSNu209hBDXz21KD7M4gKw/OeGNpZKKHrkJ2QqNwFvY9elfTppfdYVt0rHYDtApZvnsvbu/cpvcqriRNqP1JcKuLYKkgEAxSYi5JuA0AVHXJ7F2eB9ZBO16cuUfA3np1Utzsjltc9ArtoSRDATQK0FNDIWYHQNxPTSxw0529Xx63PvzNbWU38cfu05PxE9PT3/T528J1xPNKVaCfWLWCIIr5FSRjU/T6KSTtWF0SI68Y+AFJX+E2vP8/PxbfUwj7DSB01U11OgEKjEDaowguEJOVY3sMr3i2dNnP8parpeZnGORGlfJykEP8Y0wBVzhGsSWUzDgVz7z5ndDzMshxMjsbNXlV6S2mtX+OnRz6XOSk7WoJ2AoJEOQHxCTTFpXxE6yR9JdZxJO3zj9Nfn/wPaE+9N13QyMGAnRD5HIiCMhV4p+/vH5t8lj4YVXN+6Y/MyyJVyHOJv/kt4THBUfxSjMPor3DmD3jiCPxel8M7xIfaUdQjz99El8I2zIOoTCgjI4KaaXvY0f2Gs+He7dv/cTEnzN5cQMcwjkIeFdcBVxc9SvpTyujbBmioGCXkh0gwcK0KtMTGz8YB0fGbjWZ/PtydomG/wYIJt8utzRiZvDY1lj6Y1wvc7ZFQkZnMy6l4oE4qKT/ZrNEL7SbKz168V0+R8ATufnpCdV18cMmLw+x0n77kb8WNSNostXS82+T39z+S9OePA3Ek7I0JFsh8IJy1uKN+QxUMc1voqoOL3z3Frn5jbCDIEBBGdNN/RNpFryyZvAo7fvRGvt/mvWzeVvhO2aRcV1SaaGfHov2a4Lz78DOSTf5iTOjciLuBGOg/RVYXfohq0nk/U+GZ/8mmv9uuDZ9Q2QfvXrsBE+G3xV5HsSATA8RK9SA790yIH8Y3ok23XhADL4PmkGX0/6+KpKbD1dtkbYVbHoYZc0DNHqj00jDUaPRY2/rm4GRPblL82ArDd4cv0K3Agb3i8KXghOW2CCNHTFxeRU+egksuSrhM208Us96ts4MXgga+7YyBOsKRwebL8d+TWwkkbKPwrYSUTBAK6PXBBv07721sO3D4X4QE500trQaoON19MM1kA3gw2xhuTigRMbhGf79dACOAK7g0TWGiKuAVjHR3ox1AZsI9gsdlJ8kDUfm7VHbd8M5hEfuKFys47ELb8Gb084Bk6BEnvS7D0MZFfdjt43WqvLHklHLmsya7oZ6kmLHwHZfTOEV3uGFWCAEGfT0PVWTFPk2Elg52e7CfSp+dZIx+Z66lVn4DMD22jDcRETSYctYQtkBYB1pJ3TFDnPFgns4gxI3iZSM1IvV1xfM0DTTx/8ODF9/8xf7TuEExDiCYw96yWZuF6KZ4Po5LEYETNr6tT61R4/eTIN/teFJEqKToIkEqL3cilvZFcH1Lgu5ENff6cenjMuTnhWdpybhSJUhSKrLXpk4rKWUzGy1xz+66DWqP2ajhu0Rf19OMbITQBhiCp73RoSRz8UV+WEzJLUZ1gDD6j/sXAOvjjhWWk/CSCb4RV8qA0Hlg6ZJTksU+tXfQ7eJTwbts90AnspZh/6eOuK5FYbnZ0MsiavQnIiF+Lff396eeJjwT8NySsqYSRHNrE9umvW3MmNL2sSBI1kbMkT1wFHYG4nbAEMByFNeil5T2PuhuS1RffR+1Iv9l4mPfasN7Ln1QhvfBMnZH0tudB4VCu9Ikcx21s2dI6N6gU14r33Vv898VGN9ARHsh2CxB8q1RQbSa9gBzZyCfwVfRyOwN5O2CJg7FGL7dP7POvUvUqK7ZFe7NFHkn+E9Ky+RrgaEhRZfUt6YkdyKae3j3Jj62NH68RWOYq7RLgPqgWW9D7nttdLfav90J7/AwAA//83rYeYAAAABklEQVQDAMltCzwxszfdAAAAAElFTkSuQmCC"
private const val recycleOut = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADwAAAA8BAMAAADI0sRBAAAAJ1BMVEV6pf97nfh7o/12kO5bjPJlf+leVZZlk/ZHcExejvJgj/P//+0A/9FP0Or/AAAADXRSTlMeDBMG/QMBVQDLlQEBuyPuOwAAAtFJREFUOMt9Vc1q20AQHpaAcU6VdOpttQRMchPCL1AWjHw18RtYl/ZgjCEkTxBoDyEITG+mGIzzALkk51z6Uv1m9kerFvfDyDv7zcyORrMz9Ojw4+21BW4Dlix9PNLj93dZ/cZjdZuAFT7ovX1tv7bRsnaY8hp79NE6rBISWEyFp7a3XNQD8AGgl6uULASBp8GZVeZRVY6ntrcsiqwqooLwBM8LYSORKpAzraLbsiyNccuCaWY9lefG4AeFsoTI1otgCiOQeSkKePBeTT5e2UuRs4OCxJa9emitw1JoPlgkpUhddtba5ufLURk5H9ZZTkIq0pc2oFmzf9BZ9snu4ZJIjWyPZgP7jGCsoUuMg1uM7h5OnicO+t7OmR0HNXjCMQ3OJ45LH+wTjDp+BoCfm5xgrDWYNW2dDw8F/gnWiJp1G8S1jqTGS97bRmiOeotgZ9HSqRzshtg3jU78LsFY+/9r0Mi0I1PfTmsM2uibTtJw6iM7uj+hDdjmZc1JmXv77U4iuLCKDCLeud0OaneMrdu5sIbMtd17n6OuT/ku0FsbgwkxRhrOuzRXo9Q5h6b7bES40OjGPtMk/Q7DFyP7BSp7Ogeh1+fYEZxfn6VReb/+Q+MrbujKxi80hKIDvveVPZ49emboM2oKJT7IKL8LisU+G6SFRSXlEmvc17xCzpHoGemJTbHnjNo9qhDXp8PxKGUtkBqer3HZlDYZas2MxWLj7iFfUgljhksGOnfijinlLvClV8cd44s/OTU7lVx99dA1ezZmGrw2/yCXBkRJa8jzPDNeKtkW3aGSZmXEVexdxvWuoqbat6w8k2bFbMaauXQuik1tANHkvlYXKZ+0x6KoxLryW9yFpdGGjomhQK4ZV9KhK9eXnVTwyHA9ldd1kY6C0M//mhHTab/kabAK3X4aR9Ri6sVlS8lou20Hk44nGYWdpWchuNkjgh9UPb6lwtsfbTVCnXvwUeQAAAAASUVORK5CYII="
private const val BOTTOM_BAR_TAG = "BottomBarUI"

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
        var acRecirc by remember {
                mutableStateOf(
                        if ((serviceManager.getData(CarConstants.CAR_HVAC_CYCLE_MODE.getValue()) ?: "0") == "0") "1" else "0"
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
                                                CarConstants.CAR_HVAC_CYCLE_MODE.getValue() ->
                                                        acRecirc = if (value == "0") "1" else "0"
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
                                                                isACEnabled,
                                                                BottomBarState.SliderType.DRIVER_TEMP
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
                                                        FanControlSection(fanSpeed, true, BottomBarState.SliderType.FAN) { delta ->
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

                                                // 5. AC Recirc/Sync/Auto (14%)
                                                Box(
                                                        modifier = Modifier.weight(0.14f),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        Row(
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(6.dp),
                                                                verticalAlignment =
                                                                        Alignment.CenterVertically
                                                        ) {
                                                                ACControlButton(
                                                                        icon = if (acRecirc == "1") recycleIn else recycleOut,
                                                                        label = "Circular",
                                                                        isActive = acRecirc == "1",
                                                                        isEnabled = isACEnabled
                                                                ) {
                                                                        val next =
                                                                                if (acRecirc == "1")
                                                                                        "0"
                                                                                else "1"
                                                                        // Invert before sending to car (car uses opposite convention)
                                                                        val carValue = if (next == "0") "1" else "0"
                                                                        serviceManager.updateData(
                                                                                CarConstants
                                                                                        .CAR_HVAC_CYCLE_MODE
                                                                                        .getValue(),
                                                                                carValue
                                                                        )
                                                                }
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
                                                                volume,
                                                                BottomBarState.SliderType.VOLUME
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
                                                                isACEnabled,
                                                                BottomBarState.SliderType.PASS_TEMP
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
        val projectionPackageOnMain =
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                        .resolveActiveProjectionPackageForDisplay(0)
        val effectiveSelectedPackage =
                projectionPackageOnMain
                        ?: when {
                                selectedPackage.isNotEmpty() -> selectedPackage
                                else -> configs.firstOrNull()?.packageName ?: ""
                        }

        val selectedConfig = configs.find { it.packageName == effectiveSelectedPackage }
        val substituteIconVector = getSubstituteIconVector(selectedConfig?.substituteIcon)

        Row(verticalAlignment = Alignment.CenterVertically) {
                val leftNavInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val leftNavPressed by leftNavInteraction.collectIsPressedAsState()
                val leftNavColor by animateColorAsState(
                        targetValue = if (leftNavPressed) Color(0xFF2196F3).copy(alpha = 0.35f) else Color.Transparent,
                        animationSpec = tween(durationMillis = if (leftNavPressed) 50 else 300)
                )
                Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                                .width(70.dp)
                                .fillMaxHeight()
                                .clickable(
                                        interactionSource = leftNavInteraction,
                                        indication = null
                                ) {
                                        Log.w(
                                                BOTTOM_BAR_TAG,
                                                "Cluster send click: selectedPackage=$selectedPackage, effectivePackage=$effectiveSelectedPackage"
                                        )
                                        scope.launch {
                                                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                                                        .getOrCreateDefaultConfig(
                                                                context,
                                                                effectiveSelectedPackage
                                                        )
                                                        ?.let {
                                                                Log.w(
                                                                        BOTTOM_BAR_TAG,
                                                                        "Cluster send dispatch: package=${it.packageName}, display=${it.displayId}"
                                                                )
                                                                br.com.redesurftank.havalshisuku.managers
                                                                        .DisplayAppLauncher.sendToDisplay(
                                                                        it
                                                                )
                                                        }
                                        }
                                }
                ) {
                        Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(leftNavColor)
                        ) {
                                Icon(
                                        Icons.Default.KeyboardArrowLeft,
                                        contentDescription = "Cluster",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                )
                        }
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
                                                                if (effectiveSelectedPackage.isNotEmpty()) {
                                                                        Log.w(
                                                                                BOTTOM_BAR_TAG,
                                                                                "Main display launch double tap: selectedPackage=$selectedPackage, effectivePackage=$effectiveSelectedPackage"
                                                                        )
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
                                                                                                effectiveSelectedPackage
                                                                                        )
                                                                                br.com.redesurftank
                                                                                        .havalshisuku
                                                                                        .managers
                                                                                        .DisplayAppLauncher
                                                                                        .launchAnyApp(
                                                                                                context,
                                                                                                effectiveSelectedPackage
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
                        } else if (effectiveSelectedPackage.isNotEmpty()) {
                                val appInfo =
                                        remember(effectiveSelectedPackage) {
                                                br.com.redesurftank.havalshisuku.managers
                                                        .DisplayAppLauncher.resolveAppInfo(
                                                        context,
                                                        effectiveSelectedPackage,
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

                val rightNavInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val rightNavPressed by rightNavInteraction.collectIsPressedAsState()
                val rightNavColor by animateColorAsState(
                        targetValue = if (rightNavPressed) Color(0xFF2196F3).copy(alpha = 0.35f) else Color.Transparent,
                        animationSpec = tween(durationMillis = if (rightNavPressed) 50 else 300)
                )
                Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                                .width(70.dp)
                                .fillMaxHeight()
                                .clickable(
                                        interactionSource = rightNavInteraction,
                                        indication = null
                                ) {
                                        Log.w(
                                                BOTTOM_BAR_TAG,
                                                "Main display restore click: selectedPackage=$selectedPackage, effectivePackage=$effectiveSelectedPackage"
                                        )
                                        scope.launch {
                                                // bringAllToMainDisplay() returns the list of packages it actually
                                                // moved back from secondary displays. If non-empty, those apps
                                                // deserve to keep focus on Display 0 — launching selectedPackage
                                                // on top would immediately steal it (the user just brought their
                                                // active app back, they don't want it bounced). Only fall through
                                                // to launching selectedPackage when nothing was moved.
                                                val movedBack =
                                                        br.com.redesurftank.havalshisuku.managers
                                                                .DisplayAppLauncher
                                                                .bringAllToMainDisplay()
                                                Log.w(
                                                        BOTTOM_BAR_TAG,
                                                        "Main display restore result: movedBack=${movedBack.joinToString(",")}"
                                                )
                                                if (movedBack.isEmpty() && effectiveSelectedPackage.isNotEmpty()) {
                                                        br.com.redesurftank.havalshisuku.managers
                                                                .DisplayAppLauncher
                                                                .getOrCreateDefaultConfig(
                                                                        context,
                                                                        effectiveSelectedPackage
                                                                )
                                                        br.com.redesurftank.havalshisuku.managers
                                                                .DisplayAppLauncher.launchAnyApp(
                                                                context,
                                                                effectiveSelectedPackage
                                                        )
                                                }
                                        }
                                }
                ) {
                        Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(rightNavColor)
                        ) {
                                Icon(
                                        Icons.Default.KeyboardArrowRight,
                                        contentDescription = "MMI",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                )
                        }
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
                                val closeInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                val closePressed by closeInteraction.collectIsPressedAsState()
                                val closeGlow by animateColorAsState(
                                        targetValue = if (closePressed) Color(0xFF2196F3).copy(alpha = 0.35f) else Color.Transparent,
                                        animationSpec = tween(durationMillis = if (closePressed) 50 else 300)
                                )
                                Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(closeGlow)
                                                .clickable(
                                                        interactionSource = closeInteraction,
                                                        indication = null
                                                ) {
                                                        BottomBarState.isMenuExpanded = false
                                                        BottomBarState.isDeleteModeEnabled = false
                                                }
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Fechar",
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(24.dp)
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
                                                                                                        .isDeleteModeEnabled =
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
        var appMenuBounds by remember { mutableStateOf<Rect?>(null) }
        var secondaryMenuBounds by remember { mutableStateOf<Rect?>(null) }

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
                                .pointerInput(appMenuBounds, secondaryMenuBounds) {
                                        detectTapGestures { offset ->
                                                val insideAppMenu =
                                                        BottomBarState.isMenuExpanded &&
                                                                appMenuBounds?.contains(offset) == true
                                                val insideSecondaryMenu =
                                                        (BottomBarState.isSettingsMenuExpanded ||
                                                                        BottomBarState.isOverrideMenuExpanded) &&
                                                                secondaryMenuBounds?.contains(offset) == true
                                                if (!insideAppMenu && !insideSecondaryMenu) {
                                                        BottomBarState.isMenuExpanded = false
                                                        BottomBarState.isSettingsMenuExpanded = false
                                                        BottomBarState.isOverrideMenuExpanded = false
                                                        BottomBarState.isDeleteModeEnabled = false
                                                        BottomBarState.activeSliderType = null
                                                }
                                        }
                                },
                contentAlignment = Alignment.BottomCenter
        ) {
                // We use a Box with fillMaxWidth to contain our menus at the bottom
                Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp),
                ) {
                        // Custom Vertical Slider Overlay
                        if (BottomBarState.activeSliderType != null) {
                                VerticalSliderOverlay()
                        }

                        // App Menu (Left side)
                        if (br.com.redesurftank.havalshisuku.models.BottomBarState.isMenuExpanded) {
                                Box(
                                        modifier =
                                                Modifier.padding(start = 16.dp)
                                                        .align(Alignment.BottomStart)
                                                        .onGloballyPositioned {
                                                                appMenuBounds = it.boundsInRoot()
                                                        }
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
                                                        .onGloballyPositioned {
                                                                secondaryMenuBounds = it.boundsInRoot()
                                                        }
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
        sliderType: BottomBarState.SliderType? = null,
        onValueChange: (Float) -> Unit
) {
        val floatTemp = temp.toFloatOrNull() ?: -200f
        val isAbnormal = floatTemp >= 85f || floatTemp <= -40f || floatTemp == -1f || temp == "--"
        val isTempValid = isEnabled && !isAbnormal
        val alpha = if (isTempValid) 1f else 0.4f
        var centerX by remember { mutableFloatStateOf(0f) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(alpha)) {
                SmallButton(Icons.Default.Remove, isTempValid) { onValueChange(-0.5f) }
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                                .width(120.dp)
                                .onGloballyPositioned { coordinates ->
                                        centerX = coordinates.positionInRoot().x + coordinates.size.width / 2f
                                }
                                .clickable(
                                        enabled = isTempValid && sliderType != null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                ) {
                                        if (sliderType != null) {
                                                BottomBarState.sliderPositionX = centerX
                                                BottomBarState.activeSliderType = sliderType
                                        }
                                }
                ) {
                        Text(text = label, style = labelStyle)
                        val displayTemp = if (!isTempValid) "--" else temp
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
                SmallButton(Icons.Default.Add, isTempValid) { onValueChange(0.5f) }
        }
}

@Composable
fun FanControlSection(
        speed: Int,
        isEnabled: Boolean,
        sliderType: BottomBarState.SliderType? = null,
        onValueChange: (Int) -> Unit
) {
        val alpha = if (isEnabled) 1f else 0.4f
        var centerX by remember { mutableFloatStateOf(0f) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(alpha)) {
                SmallButton(Icons.Default.Remove, isEnabled) { onValueChange(-1) }
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                                .width(120.dp)
                                .onGloballyPositioned { coordinates ->
                                        centerX = coordinates.positionInRoot().x + coordinates.size.width / 2f
                                }
                                .clickable(
                                        enabled = isEnabled && sliderType != null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                ) {
                                        if (sliderType != null) {
                                                BottomBarState.sliderPositionX = centerX
                                                BottomBarState.activeSliderType = sliderType
                                        }
                                }
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
fun VolumeControlSection(
        label: String,
        volume: Int,
        sliderType: BottomBarState.SliderType? = null,
        onValueChange: (Int) -> Unit
) {
        var centerX by remember { mutableFloatStateOf(0f) }
        Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton(Icons.Default.Remove) { onValueChange(-1) }
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                                .width(60.dp)
                                .onGloballyPositioned { coordinates ->
                                        centerX = coordinates.positionInRoot().x + coordinates.size.width / 2f
                                }
                                .clickable(
                                        enabled = sliderType != null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                ) {
                                        if (sliderType != null) {
                                                BottomBarState.sliderPositionX = centerX
                                                BottomBarState.activeSliderType = sliderType
                                        }
                                }
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
                horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
                val voltarInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val voltarPressed by voltarInteraction.collectIsPressedAsState()
                val voltarColor by animateColorAsState(
                        targetValue = if (voltarPressed) Color(0xFF2196F3).copy(alpha = 0.35f) else Color.Transparent,
                        animationSpec = tween(durationMillis = if (voltarPressed) 50 else 300)
                )
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                                .width(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(voltarColor)
                                .clickable(
                                        interactionSource = voltarInteraction,
                                        indication = null
                                ) {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                ShizukuUtils.runCommandAndGetOutput(
                                                        arrayOf("input", "keyevent", "4")
                                                )
                                        }
                                }
                                .padding(vertical = 6.dp)
                ) {
                        Text(
                                text = "Voltar",
                                style = labelStyle.copy(fontSize = 10.sp, color = Color.White)
                        )
                        Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                        )
                }

                val showSettings = BottomBarState.isSettingsMenuExpanded
                val conducaoInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val conducaoPressed by conducaoInteraction.collectIsPressedAsState()
                val conducaoColor by animateColorAsState(
                        targetValue = if (conducaoPressed) Color(0xFF2196F3).copy(alpha = 0.35f) else Color.Transparent,
                        animationSpec = tween(durationMillis = if (conducaoPressed) 50 else 300)
                )
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                                .width(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(conducaoColor)
                                .clickable(
                                        interactionSource = conducaoInteraction,
                                        indication = null
                                ) {
                                        BottomBarState.isSettingsMenuExpanded = !showSettings
                                }
                                .padding(vertical = 6.dp)
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
                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = if (showSettings) Color(0xFF2196F3) else Color.White,
                                        modifier = Modifier.size(24.dp)
                                )
                        }
                }
        }
}

@Composable
fun NavIcon(icon: ImageVector, onClick: () -> Unit) {
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val glowColor = Color(0xFF2196F3).copy(alpha = 0.35f)
        val animatedColor by animateColorAsState(
                targetValue = if (isPressed) glowColor else Color.Transparent,
                animationSpec = tween(durationMillis = if (isPressed) 50 else 300)
        )

        Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                        .size(48.dp) // standard nav icon container width/height
                        .clip(RoundedCornerShape(8.dp))
                        .background(animatedColor)
                        .clickable(
                                interactionSource = interactionSource,
                                indication = null
                        ) { onClick() }
        ) {
                Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                )
        }
}

private fun cropBitmapTransparentMargins(source: android.graphics.Bitmap): android.graphics.Bitmap {
        var firstX = source.width
        var firstY = source.height
        var lastX = -1
        var lastY = -1

        for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                        val alpha = (source.getPixel(x, y) shr 24) and 0xFF
                        if (alpha > 10) { // non-transparent
                                if (x < firstX) firstX = x
                                if (y < firstY) firstY = y
                                if (x > lastX) lastX = x
                                if (y > lastY) lastY = y
                        }
                }
        }

        if (lastX < firstX || lastY < firstY) {
                return source // empty or fully transparent
        }

        val width = lastX - firstX + 1
        val height = lastY - firstY + 1
        return android.graphics.Bitmap.createBitmap(source, firstX, firstY, width, height)
}

private fun decodeBase64ToBitmap(base64Str: String): ImageBitmap? {
        return try {
                val cleanStr = if (base64Str.startsWith("data:image")) {
                        base64Str.substringAfter(",")
                } else {
                        base64Str
                }
                val decodedBytes = android.util.Base64.decode(cleanStr, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                        val cropped = cropBitmapTransparentMargins(bitmap)
                        android.util.Log.d("BottomBarUI", "Successfully decoded and cropped. Orig size: ${bitmap.width}x${bitmap.height}, Cropped size: ${cropped.width}x${cropped.height}")
                        cropped.asImageBitmap()
                } else {
                        android.util.Log.e("BottomBarUI", "decodeByteArray returned null for base64: ${base64Str.take(50)}...")
                        null
                }
        } catch (e: Exception) {
                android.util.Log.e("BottomBarUI", "Failed to decode base64: ${base64Str.take(50)}...", e)
                null
        }
}

@Composable
fun ACControlButton(
        icon: Any,
        label: String,
        isActive: Boolean,
        isEnabled: Boolean,
        onClick: () -> Unit
) {
        val context = LocalContext.current
        val alpha = if (isEnabled) 1f else 0.4f
        val activeColor = Color(0xFF2196F3) // Vibrant blue for active state
        val contentColor = if (isActive && isEnabled) activeColor else Color.White

        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val glowColor = Color(0xFF2196F3).copy(alpha = 0.35f)
        val animatedColor by animateColorAsState(
                targetValue = if (isPressed) glowColor else Color.Transparent,
                animationSpec = tween(durationMillis = if (isPressed) 50 else 300)
        )

        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                        .alpha(alpha)
                        .width(68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(animatedColor)
                        .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                enabled = isEnabled
                        ) { onClick() }
                        .padding(vertical = 6.dp)
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
                if (icon is ImageVector) {
                        Icon(
                                icon,
                                contentDescription = label,
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                        )
                } else if (icon is String) {
                        val bitmap = remember(icon) { decodeBase64ToBitmap(icon) }
                        if (bitmap != null) {
                                Image(
                                        bitmap = bitmap,
                                        contentDescription = label,
                                        colorFilter = ColorFilter.tint(contentColor),
                                        modifier = Modifier.size(width = 36.dp, height = 24.dp)
                                )
                        }
                }
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
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val glowColor = Color(0xFF2196F3).copy(alpha = 0.35f)
        val animatedColor by animateColorAsState(
                targetValue = if (isPressed) glowColor else Color.Transparent,
                animationSpec = tween(durationMillis = if (isPressed) 50 else 300)
        )

        Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                        .size(70.dp, 60.dp)
                        .background(Color.Black.copy(alpha = 0.95f))
                        .clickable(
                                enabled = enabled,
                                interactionSource = interactionSource,
                                indication = null
                        ) { onClick() }
        ) {
                val alpha = if (enabled) 1f else 0.4f
                Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                                .fillMaxSize()
                                .alpha(alpha)
                ) {
                        Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(animatedColor)
                        ) {
                                Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(iconSize)
                                )
                        }
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

                // v2.3: Re-resize Impulse-managed Display 0 apps so their
                // windows shrink for the new overscan. wm overscan above is
                // the system-level fallback for unmanaged apps; managed apps
                // (e.g. AA) need an explicit am stack resize because Impulse's
                // bounds otherwise override the system overscan.
                br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher
                        .reapplyDisplay0BoundsForOverscanAsync()
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

enum class VisualAidType {
        TEMP,
        FAN,
        VOLUME
}

@Composable
fun VerticalSliderOverlay() {
        val activeSlider = BottomBarState.activeSliderType ?: return
        val positionX = BottomBarState.sliderPositionX
        val serviceManager = ServiceManager.getInstance()
        val isACEnabled = serviceManager.getData(CarConstants.CAR_HVAC_POWER_MODE.getValue()) == "1"

        LaunchedEffect(activeSlider, BottomBarState.sliderInteractionTrigger) {
                delay(3000)
                if (!BottomBarState.isSliderDragging) {
                        BottomBarState.activeSliderType = null
                }
        }

        val density = LocalDensity.current
        val sliderWidthDp = 80.dp
        val sliderWidthPx = with(density) { sliderWidthDp.toPx() }
        val screenWidthPx = LocalConfiguration.current.screenWidthDp * density.density
        val finalX = (positionX - sliderWidthPx / 2f).coerceIn(0f, screenWidthPx - sliderWidthPx)
        val finalXDp = with(density) { finalX.toDp() }

        Box(
                modifier = Modifier.fillMaxSize()
        ) {
                Box(
                        modifier = Modifier
                                .offset(x = finalXDp)
                                .align(Alignment.BottomStart)
                                .padding(bottom = 8.dp)
                ) {
                        when (activeSlider) {
                                BottomBarState.SliderType.DRIVER_TEMP -> {
                                        var tempStr by remember {
                                                mutableStateOf(
                                                        serviceManager.getData(CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.getValue())
                                                                ?: "22.0"
                                                )
                                        }
                                        DisposableEffect(Unit) {
                                                val listener = object : br.com.redesurftank.havalshisuku.listeners.IDataChanged {
                                                        override fun onDataChanged(key: String, value: String?) {
                                                                if (key == CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.getValue() && value != null) {
                                                                        tempStr = value
                                                                }
                                                        }
                                                }
                                                serviceManager.addDataChangedListener(listener)
                                                onDispose { serviceManager.removeDataChangedListener(listener) }
                                        }
                                        val tempVal = tempStr.toFloatOrNull() ?: 22.0f
                                        VerticalSlider(
                                                label = "Motorista",
                                                value = tempVal.coerceIn(16.0f, 32.0f),
                                                range = 16.0f..32.0f,
                                                step = 0.5f,
                                                displayValue = if (!isACEnabled || tempStr == "--" || tempVal <= -1) "--" else String.format(java.util.Locale.US, "%.1f°C", tempVal),
                                                visualAidType = VisualAidType.TEMP,
                                                modifier = Modifier.height(380.dp),
                                                isEnabled = isACEnabled,
                                                onValueChange = { newValue ->
                                                        serviceManager.updateData(
                                                                CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.getValue(),
                                                                String.format(java.util.Locale.US, "%.1f", newValue)
                                                        )
                                                }
                                        )
                                }
                                BottomBarState.SliderType.PASS_TEMP -> {
                                        var tempStr by remember {
                                                mutableStateOf(
                                                        serviceManager.getData(CarConstants.CAR_HVAC_PASS_TEMPERATURE.getValue())
                                                                ?: "22.0"
                                                )
                                        }
                                        DisposableEffect(Unit) {
                                                val listener = object : br.com.redesurftank.havalshisuku.listeners.IDataChanged {
                                                        override fun onDataChanged(key: String, value: String?) {
                                                                if (key == CarConstants.CAR_HVAC_PASS_TEMPERATURE.getValue() && value != null) {
                                                                        tempStr = value
                                                                }
                                                        }
                                                }
                                                serviceManager.addDataChangedListener(listener)
                                                onDispose { serviceManager.removeDataChangedListener(listener) }
                                        }
                                        val tempVal = tempStr.toFloatOrNull() ?: 22.0f
                                        VerticalSlider(
                                                label = "Passageiro",
                                                value = tempVal.coerceIn(16.0f, 32.0f),
                                                range = 16.0f..32.0f,
                                                step = 0.5f,
                                                displayValue = if (!isACEnabled || tempStr == "--" || tempVal <= -1) "--" else String.format(java.util.Locale.US, "%.1f°C", tempVal),
                                                visualAidType = VisualAidType.TEMP,
                                                modifier = Modifier.height(380.dp),
                                                isEnabled = isACEnabled,
                                                onValueChange = { newValue ->
                                                        serviceManager.updateData(
                                                                CarConstants.CAR_HVAC_PASS_TEMPERATURE.getValue(),
                                                                String.format(java.util.Locale.US, "%.1f", newValue)
                                                        )
                                                }
                                        )
                                }
                                BottomBarState.SliderType.FAN -> {
                                        var speedVal by remember {
                                                mutableIntStateOf(
                                                        serviceManager.getData(CarConstants.CAR_HVAC_FAN_SPEED.getValue())?.toIntOrNull()
                                                                ?: 1
                                                )
                                        }
                                        var hvacPower by remember {
                                                mutableStateOf(
                                                        serviceManager.getData(CarConstants.CAR_HVAC_POWER_MODE.getValue()) ?: "1"
                                                )
                                        }
                                        DisposableEffect(Unit) {
                                                val listener = object : br.com.redesurftank.havalshisuku.listeners.IDataChanged {
                                                        override fun onDataChanged(key: String, value: String?) {
                                                                if (value == null) return
                                                                if (key == CarConstants.CAR_HVAC_FAN_SPEED.getValue()) {
                                                                        speedVal = value.toIntOrNull() ?: speedVal
                                                                } else if (key == CarConstants.CAR_HVAC_POWER_MODE.getValue()) {
                                                                        hvacPower = value
                                                                }
                                                        }
                                                }
                                                serviceManager.addDataChangedListener(listener)
                                                onDispose { serviceManager.removeDataChangedListener(listener) }
                                        }
                                        VerticalSlider(
                                                label = "Ventilação",
                                                value = speedVal.toFloat(),
                                                range = 0f..7f,
                                                step = 1f,
                                                displayValue = speedVal.toString(),
                                                visualAidType = VisualAidType.FAN,
                                                onValueChange = { newValue ->
                                                        val calculatedSpeed = newValue.toInt().coerceIn(0, 7)
                                                        serviceManager.updateData(
                                                                CarConstants.CAR_HVAC_FAN_SPEED.getValue(),
                                                                calculatedSpeed.toString()
                                                        )
                                                        if (calculatedSpeed == 0 && hvacPower == "1") {
                                                                serviceManager.updateData(
                                                                        CarConstants.CAR_HVAC_POWER_MODE.getValue(),
                                                                        "0"
                                                                )
                                                        } else if (calculatedSpeed > 0 && hvacPower == "0") {
                                                                serviceManager.updateData(
                                                                        CarConstants.CAR_HVAC_POWER_MODE.getValue(),
                                                                        "1"
                                                                )
                                                        }
                                                }
                                        )
                                }
                                BottomBarState.SliderType.VOLUME -> {
                                        var volVal by remember {
                                                mutableIntStateOf(
                                                        serviceManager.getData(CarConstants.SYS_SETTINGS_AUDIO_MEDIA_VOLUME.getValue())?.toIntOrNull()
                                                                ?: 10
                                                )
                                        }
                                        DisposableEffect(Unit) {
                                                val listener = object : br.com.redesurftank.havalshisuku.listeners.IDataChanged {
                                                        override fun onDataChanged(key: String, value: String?) {
                                                                if (key == CarConstants.SYS_SETTINGS_AUDIO_MEDIA_VOLUME.getValue() && value != null) {
                                                                        volVal = value.toIntOrNull() ?: volVal
                                                                }
                                                        }
                                                }
                                                serviceManager.addDataChangedListener(listener)
                                                onDispose { serviceManager.removeDataChangedListener(listener) }
                                        }
                                        VerticalSlider(
                                                label = "Volume",
                                                value = volVal.toFloat(),
                                                range = 0f..30f,
                                                step = 1f,
                                                displayValue = volVal.toString(),
                                                visualAidType = VisualAidType.VOLUME,
                                                onValueChange = { newValue ->
                                                        val calculatedVol = newValue.toInt().coerceIn(0, 30)
                                                        serviceManager.updateData(
                                                                CarConstants.SYS_SETTINGS_AUDIO_MEDIA_VOLUME.getValue(),
                                                                calculatedVol.toString()
                                                        )
                                                }
                                        )
                                }
                        }
                }
        }
}

@Composable
fun VerticalSlider(
        label: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        displayValue: String,
        visualAidType: VisualAidType,
        onValueChange: (Float) -> Unit,
        modifier: Modifier = Modifier,
        isEnabled: Boolean = true
) {
        var trackHeightPx by remember { mutableFloatStateOf(0f) }
        Box(
                modifier = Modifier
                        .width(80.dp)
                        .height(240.dp)
                        .then(modifier)
                        .background(Color(0xFF13151A).copy(alpha = 0.95f), RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0xFF1D2430), RoundedCornerShape(20.dp))
                        .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
        ) {
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxSize()
                ) {
                        Box(
                                modifier = Modifier
                                        .width(48.dp)
                                        .weight(1f)
                                        .padding(vertical = 10.dp)
                                        .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
                                        .pointerInput(range, step, isEnabled) {
                                                 if (!isEnabled) return@pointerInput
                                                 awaitPointerEventScope {
                                                         while (true) {
                                                                 val down = awaitFirstDown(requireUnconsumed = false)
                                                                 BottomBarState.isSliderDragging = true
                                                                 BottomBarState.sliderInteractionTrigger++
                                                                 val startY = down.position.y
                                                                 val fractionStart = ((trackHeightPx - startY) / trackHeightPx).coerceIn(0f..1f)
                                                                 val rawValueStart = range.start + fractionStart * (range.endInclusive - range.start)
                                                                 val steppedStart = (rawValueStart / step).roundToInt() * step
                                                                 onValueChange(steppedStart.coerceIn(range))

                                                                 do {
                                                                         val event = awaitPointerEvent()
                                                                         val change = event.changes.firstOrNull() ?: break
                                                                         if (change.pressed) {
                                                                                 change.consume()
                                                                                 val currentY = change.position.y
                                                                                 val fraction = ((trackHeightPx - currentY) / trackHeightPx).coerceIn(0f..1f)
                                                                                 val rawValue = range.start + fraction * (range.endInclusive - range.start)
                                                                                 val stepped = (rawValue / step).roundToInt() * step
                                                                                 onValueChange(stepped.coerceIn(range))
                                                                                 BottomBarState.sliderInteractionTrigger++
                                                                         }
                                                                 } while (event.changes.any { it.pressed })

                                                                 BottomBarState.isSliderDragging = false
                                                                 BottomBarState.sliderInteractionTrigger++
                                                         }
                                                 }
                                         }
                        ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                        val w = size.width
                                        val h = size.height
                                        val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

                                        when (visualAidType) {
                                                VisualAidType.TEMP -> {
                                                        val trackWidth = 10.dp.toPx()
                                                        val trackRect = Rect(
                                                                left = (w - trackWidth) / 2f,
                                                                top = 0f,
                                                                right = (w + trackWidth) / 2f,
                                                                bottom = h
                                                        )
                                                        drawRoundRect(
                                                                color = Color.White.copy(alpha = 0.1f),
                                                                topLeft = Offset(trackRect.left, trackRect.top),
                                                                size = Size(trackRect.width, trackRect.height),
                                                                cornerRadius = CornerRadius(trackWidth / 2f)
                                                        )

                                                        // Draw sleek step ticks flanking the temperature track
                                                        val numTicks = 9 // every 2°C from 16 to 32
                                                        for (i in 0 until numTicks) {
                                                                val tickY = h - (i.toFloat() / (numTicks - 1)) * h
                                                                // Left tick
                                                                drawLine(
                                                                        color = Color.White.copy(alpha = 0.25f),
                                                                        start = Offset(trackRect.left - 8.dp.toPx(), tickY),
                                                                        end = Offset(trackRect.left - 3.dp.toPx(), tickY),
                                                                        strokeWidth = 1.dp.toPx()
                                                                )
                                                                // Right tick
                                                                drawLine(
                                                                        color = Color.White.copy(alpha = 0.25f),
                                                                        start = Offset(trackRect.right + 3.dp.toPx(), tickY),
                                                                        end = Offset(trackRect.right + 8.dp.toPx(), tickY),
                                                                        strokeWidth = 1.dp.toPx()
                                                                )
                                                        }

                                                        if (isEnabled) {
                                                                val activeHeight = h * fraction
                                                                val gradientBrush = Brush.verticalGradient(
                                                                        colors = listOf(Color(0xFFFF4B4B), Color(0xFF4A9EFF)),
                                                                        startY = 0f,
                                                                        endY = h
                                                                )
                                                                drawRoundRect(
                                                                        brush = gradientBrush,
                                                                        topLeft = Offset(trackRect.left, h - activeHeight),
                                                                        size = Size(trackRect.width, activeHeight),
                                                                        cornerRadius = CornerRadius(trackWidth / 2f)
                                                                )
                                                                drawCircle(
                                                                        color = Color.White,
                                                                        radius = 7.dp.toPx(),
                                                                        center = Offset(w / 2f, h - activeHeight)
                                                                )
                                                        }
                                                }
                                                VisualAidType.FAN, VisualAidType.VOLUME -> {
                                                        val numSteps = if (visualAidType == VisualAidType.FAN) 7 else 15
                                                        val spacing = 3.dp.toPx()
                                                        val stepHeight = (h - (numSteps - 1) * spacing) / numSteps

                                                        for (i in 0 until numSteps) {
                                                                val active = (i + 1).toFloat() / numSteps <= fraction || (fraction == 0f && i == 0 && value > 0)
                                                                val minStepWidth = 12.dp.toPx()
                                                                val maxStepWidth = 36.dp.toPx()
                                                                val stepWidth = minStepWidth + (maxStepWidth - minStepWidth) * (i.toFloat() / (numSteps - 1))

                                                                val stepTop = h - (i + 1) * (stepHeight + spacing) + spacing
                                                                val stepLeft = (w - stepWidth) / 2f

                                                                val color = if (active && isEnabled) {
                                                                        Color(0xFF2196F3)
                                                                } else {
                                                                        Color.White.copy(alpha = 0.15f)
                                                                }

                                                                drawRoundRect(
                                                                        color = color,
                                                                        topLeft = Offset(stepLeft, stepTop),
                                                                        size = Size(stepWidth, stepHeight),
                                                                        cornerRadius = CornerRadius(2.dp.toPx())
                                                                )
                                                        }
                                                }
                                        }
                                }
                        }
                }
        }
}
