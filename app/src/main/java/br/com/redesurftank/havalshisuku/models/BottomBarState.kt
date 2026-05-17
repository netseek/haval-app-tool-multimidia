package br.com.redesurftank.havalshisuku.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

object BottomBarState {
    var isVisible by mutableStateOf(true)
    var isMenuExpanded by mutableStateOf(false)
    var isSettingsMenuExpanded by mutableStateOf(false)
    var isOverrideMenuExpanded by mutableStateOf(false)
    var selectedPackage by mutableStateOf("")
    var currentPackage by mutableStateOf("")
    var autoHideEnabled by mutableStateOf(false)
    var isFridaRunning by mutableStateOf(false)
    var isDeleteModeEnabled by mutableStateOf(false)
    val restoredApps = mutableStateListOf<String>()
}

