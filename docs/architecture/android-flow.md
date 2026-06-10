# Android Flow

Atualizado em: 2026-06-01

## Fluxo Identificado

1. `App.onCreate()` salva application context, aplica mounts Android Auto quando instalados e inicia `ForegroundService`.
2. `ForegroundService` inicia como foreground service e tenta inicializar Shizuku via telnet local.
3. Após Shizuku, o serviço inicia rotinas como ADB/SSH, iptables, patches e demais serviços internos.
4. `ServiceManager` integra serviços veiculares, cache de dados e eventos para UI/projectors.
5. `ProjectorManager` cria projectors nos displays secundários.
6. Telas Compose em `ui/screens` expõem configurações para usuário.

## Atualizacao 2026-05-31

- `ForegroundService` usa lock dedicado para serializar bootstrap/restart sem segurar o monitor do
  service em chamadas lentas de Shizuku/telnet.
- `SplashActivity` redireciona imediatamente para `MainActivity`; tela "Inicializando sistema"
  persistente nao deve mais bloquear a UI quando o service demora.
- `ServiceManager` tolera a ausencia temporaria de `VoiceAdapterService` e inicializa
  `ProjectorManager` na main thread. `IntelligentVehicleControlService` continua obrigatorio.
- `com.beantechs.voice.adapter` deve ser restaurado para dados completos de veiculo/DVR/modelo,
  mesmo que a UI de voz esteja desativada durante diagnostico.

## Atualizacao 2026-06-01

- Auto-start Shizuku depende do Impulse instalado com UID baixo. O `ForegroundService` recalcula o
  UID real no boot e invalida `selfInstallationIntegrityCheck` se `uid > 10999`.
- Quando o UID nao permite bootstrap automatico, o foreground service encerra explicitamente em vez
  de ficar rodando em estado parcial.
- A execucao de `libshizuku.so` captura stderr e valida `shizuku_server` apos o starter. Saidas com
  `fatal:` ou `Can't find service` sao tratadas como falha de bootstrap.
- `pm install -r` nao deve ser usado como tentativa de corrigir UID, porque preserva o usuario
  Linux do pacote. Para recuperar auto-start quando o UID estiver alto, usar reinstall limpo pelo
  fluxo com hook/exploit e depois validar `dumpsys package` e `pidof shizuku_server`.

## Arquivos Relacionados

- `App.java`
- `ForegroundService.java`
- `BootReceiver.java`
- `ServiceManager.java`
- `MainActivity.kt`
- `SplashActivity.kt`
- `ui/screens/TelasScreen.kt`
- `ui/screens/InstallAppsScreen.kt`

## Riscos

- Falha em Shizuku impede comandos privilegiados.
- Alterar `ForegroundService` pode quebrar boot.
- Alterar receivers pode impedir start após atualização/reboot.
- `ServiceManager` tem grande superfície de integração veicular.

## A Confirmar

- Sequência exata de inicialização em cold boot real na central.
- Quais permissões precisam ser concedidas manualmente em cada instalação.
