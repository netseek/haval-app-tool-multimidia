package br.com.redesurftank.havalshisuku.navigation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalshisuku.ui.components.AppColors
import br.com.redesurftank.havalshisuku.ui.components.StyledCard
import kotlinx.coroutines.delay

@Composable
fun CarPlayCaptureScreen(onBackToFeatures: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(CarPlayNavigationCapture.status) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            status = CarPlayNavigationCapture.status
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.Background)
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedButton(onClick = onBackToFeatures) { Text("Voltar aos recursos") }
        Text("Teste CarPlay · captura + HUD", color = AppColors.TextPrimary,
            fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Um toque prepara toda a captura. Depois conecte o CarPlay e faça uma rota no Apple Maps ou Waze.",
            color = AppColors.TextSecondary, fontSize = 18.sp)
        StyledCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Teste único · 5 minutos", color = AppColors.TextPrimary, fontSize = 20.sp)
                Text("Inicia junto a captura privada e o texto experimental no HUD. O mesmo botão encerra e limpa a sessão; " +
                    "se você não tocar novamente, o teste termina sozinho após 5 minutos. Android Auto continua com prioridade.",
                    color = AppColors.TextSecondary, fontSize = 16.sp)
                Button(onClick = {
                    if (status.running) CarPlayNavigationCapture.stop()
                    else CarPlayNavigationCapture.startFiveMinuteTest(context)
                }) {
                    Text(if (status.running) "Encerrar teste" else "Iniciar teste CarPlay")
                }
            }
        }
        StyledCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O que é ativado", color = AppColors.TextPrimary, fontSize = 20.sp)
                Text("• captura dos callbacks de navegação do CarPlay\n" +
                    "• distância e nome da via no HUD nativo, quando disponíveis\n" +
                    "• captura correlacionada dos eventos do cluster durante os mesmos 5 minutos\n\n" +
                    "A seta continua vazia enquanto os códigos de manobra não forem confirmados. Esta 129 não ativa nem simula o mapa limpo: " +
                    "os logs servirão para construir e validar a próxima etapa da ponte do cluster.",
                    color = AppColors.TextSecondary)
            }
        }
        StyledCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.message, color = AppColors.TextPrimary, fontSize = 20.sp)
                Text("Serviço: ${if (status.connected) "Binder conectado" else "sem conexão"}", color = AppColors.TextSecondary)
                Text("Callbacks: ${status.callbacks} · Erros: ${status.errors} · Descartados: ${status.dropped}", color = AppColors.TextSecondary)
                Text("Nulos: ${status.summary.empty} · Sem rota elegível: ${status.summary.inactive}", color = AppColors.TextSecondary)
                Text("Potencialmente úteis: ${status.summary.candidates} · Não confirmados: ${status.summary.unconfirmed}", color = AppColors.TextSecondary)
                Text("HUD: ${if (CarPlayExperimentalHud.enabled) "ativo" else "inativo"}. Serviço conectado ou callback recebido não garante mapa limpo.",
                    color = AppColors.TextSecondary)
                val end = if (status.running) now else status.stoppedAtMs
                val duration = if (status.startedAtMs == 0L) 0L else ((end - status.startedAtMs).coerceAtLeast(0L) / 1_000L)
                Text("Duração: ${duration / 60}min ${duration % 60}s", color = AppColors.TextSecondary)
                if (status.running && status.startedAtMs != 0L) {
                    val remaining = ((CarPlayNavigationCapture.FIVE_MINUTE_TEST_MS - (now - status.startedAtMs))
                        .coerceAtLeast(0L) / 1_000L)
                    Text("Tempo restante: ${remaining / 60}min ${remaining % 60}s", color = AppColors.TextSecondary)
                }
                val age = if (status.lastCallbackMs == 0L) "nenhum" else "há ${(now - status.lastCallbackMs) / 1_000}s"
                Text("Último callback: $age", color = AppColors.TextSecondary)
                Text("Estado bruto: ${status.routeState ?: "—"} · Manobra bruta: ${status.maneuver ?: "—"}", color = AppColors.TextSecondary)
                if (status.session.isNotEmpty()) Text("Sessão: ${status.session}", color = AppColors.TextSecondary)
            }
        }
    }
}
