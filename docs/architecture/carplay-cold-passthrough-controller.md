# Controlador do primeiro passthrough CarPlay

## Escopo

`scripts/carplay-native-analysis/cold_passthrough_controller.py` coordena o
primeiro teste do interceptor frio no processo OEM. Ele instala os nove pontos
revisados inicialmente na rota de passthrough. Depois, fixa um controle Binder
inerte que não anuncia `AltScreen`, não cria decoder, não abre/move Activity e
não altera Surface, resolução, SystemUI, Android Auto ou display 3 até o pedido
explícito do teste de cinco minutos no Impulse.

Esse teste responde uma pergunta anterior ao mapa limpo: os hooks podem nascer
antes das bibliotecas OEM, permanecer presos ao processo e preservar o CarPlay
principal no display 0?

## Entradas fixadas

- serviço OEM montado e cópia staged com MD5
  `b61f0e4d63535a2ef447be559211697b`;
- perfil frio v3 com digest canônico
  `31b878ba35c1a7fec07d7e854264837ef3702962a1e059fa5d020afa0d7c4d0f`;
- UUID principal revisado `e5f7a68d-7b0f-4305-984b-974f677a150b`;
- bridge ARM64 vindo de um job imutável do Mac Mini e conferido por SHA-256;
- `fridainjector` já autorizado na central.

O controlador transfere somente hexadecimal ASCII compactado, confere SHA-256
no destino e especializa o launcher com o PID novo. O hash do launcher exato é
conferido de novo antes da injeção.

## Barreiras

O fluxo recusa execução quando encontra task visual CarPlay ou Android Auto.
Antes do restart, pré-carrega bridge e launcher; dentro da janela de dez segundos
faz apenas: PID novo, ausência das três bibliotecas, especialização do launcher,
injeção, estado `armed`, arquivo de release e estado `installed-pinned`.

Em qualquer saída a propriedade opt-in é limpa. Falha antes da publicação deixa
o serviço seguir stock. `failed-pinned` nunca causa retry, kill ou unload automático;
esse estado exige inspeção. Após sucesso, o injector deve permanecer vivo por toda
a vida daquele processo, conforme o contrato do trampoline Frida.

## Uso controlado

Preparar sem reiniciar:

```bash
python3 scripts/carplay-native-analysis/cold_passthrough_controller.py \
  --host HEADUNIT_TAILSCALE_IP \
  --bridge /caminho/validado/libcarplay_video_bridge.so \
  --stage-only
```

Executar o passthrough frio, apenas com projeções desconectadas:

```bash
python3 scripts/carplay-native-analysis/cold_passthrough_controller.py \
  --host HEADUNIT_TAILSCALE_IP \
  --bridge /caminho/validado/libcarplay_video_bridge.so \
  --execute-cold-restart
```

O sucesso técnico termina em `installed-pinned`. Depois disso ainda é necessário
conectar o iPhone e comprovar startup, vídeo principal D0, finalização e reconexão.
Nada nesse resultado comprova segundo stream ou mapa no cluster.

## Validação offline

- 4 testes novos do template/PID/hash/fases;
- 24 testes Python do diretório passaram no ambiente isolado, um experimento
  opcional de conversor ficou explicitamente ignorado por não receber seu binário;
- 26 cenários Node do interceptor frio passaram;
- ELF ARM64 do commit `6d7254627a97c9843c182709e5360976ccba3c3b`, job
  `haval-74126-work-6d7254627a97c984-20261023T175741Z-31845`, SHA-256
  `015beb71138ce812fb25f3c2fd9afaa304a064e7859b534154c6c9981a2f0eac`;
- exports de ABI, estado, preparação, bootstrap e entradas de dados/display
  presentes no ELF.

Não houve execução no OEM: a central estava offline no Tailscale durante a
tentativa de preflight somente-leitura.
