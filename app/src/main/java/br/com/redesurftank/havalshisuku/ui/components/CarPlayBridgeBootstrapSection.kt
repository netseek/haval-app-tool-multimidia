package br.com.redesurftank.havalshisuku.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalshisuku.managers.CarPlayBridgeBootstrapManager

@Composable
fun CarPlayBridgeBootstrapSection() {
    val context = LocalContext.current.applicationContext
    val status by CarPlayBridgeBootstrapManager.status.collectAsState()
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context) {
        runCatching { CarPlayBridgeBootstrapManager.start(context) }
            .onFailure {
                Log.e("CarPlayBridgeBootstrap", "Unable to observe CarPlay preparation", it)
                actionError = "Não foi possível consultar a preparação do CarPlay."
            }
    }

    StyledCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Preparar CarPlay ao ligar", color = AppColors.TextPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = status.enabled, onCheckedChange = { enabled ->
                    actionError = null
                    runCatching {
                        CarPlayBridgeBootstrapManager.setAutomaticEnabled(context, enabled)
                        CarPlayBridgeBootstrapManager.start(context)
                    }.onFailure {
                        Log.e("CarPlayBridgeBootstrap", "Unable to change automatic CarPlay preparation", it)
                        actionError = "Não foi possível alterar a preparação automática. Tente novamente."
                    }
                })
            }
            Text("Prepara o recurso automaticamente ao iniciar a central. " +
                "O teste de cinco minutos continua sendo iniciado por você.",
                color = AppColors.TextSecondary, fontSize = 16.sp)
            Text(actionError ?: status.message, color = AppColors.TextSecondary, fontSize = 16.sp)
            if (status.enabled && (status.state == "blocked" || status.state == "failed")) {
                OutlinedButton(onClick = {
                    actionError = null
                    runCatching {
                        CarPlayBridgeBootstrapManager.resetFailureCircuit(context)
                        CarPlayBridgeBootstrapManager.start(context)
                    }.onFailure {
                        Log.e("CarPlayBridgeBootstrap", "Unable to retry CarPlay preparation", it)
                        actionError = "Não foi possível retomar a preparação. Tente novamente."
                    }
                }) {
                    Text("Tentar preparação novamente")
                }
            }
        }
    }
}
