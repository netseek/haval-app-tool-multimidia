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
esse estado exige inspeção. Nas revisões atuais, os scripts usam o modo
`eternalize`: o cliente do injector pode terminar, mas o script precisa responder
a um desafio criado depois da saída, sem `dispose`, e manter callbacks/trampolins
pinados por toda a vida do processo. Um cliente vivo isoladamente também não é
aceito como prova de residência.

## Confirmação de estado independente do stdout

### Correção após o teste de 25/10

O estado agora exige o PID original vivo, o mesmo `starttime` de `/proc/PID/stat`
e o `cmdline` OEM exato, antes e depois da leitura de cada confirmação. Um arquivo
`armed` ou `installed-pinned` deixado por um processo morto não autoriza o processo
que o Android criou em seguida. O controlador encerra a espera imediatamente,
limpa o gate e não reinicia nem descarrega o processo substituto. As verificações
iniciais compartilham as consultas existentes; não acrescentam viagens de rede à
janela fria de oito segundos.

A instalação também saiu de `onAdded`: ela acontece no retorno interno revisado
de `do_dlopen`, com a carga concluída e o mutex do linker ainda retido pelo caller.
O perfil adicional fixa o linker da central e a closure OEM admitida. Bibliotecas
inesperadas, carga parcial ou callers diferentes interrompem a preparação antes
de modificar entradas OEM. Ver
[o contrato do interceptor](../../scripts/carplay-native-analysis/native/COLD_INTERCEPTION.md).

Essa correção tem testes de modelo e evidência estática do carregador real. Ainda
exige novo resultado físico `installed-pinned`, endpoint armado e CarPlay D0
íntegro antes de afirmar que a bridge ou o mapa secundário funcionam.

O launcher grava cada mudança de fase em `cold-state.json`, no diretório privado
de staging, com `flush()` e fechamento explícitos antes de emitir a mensagem
Frida. O controlador consulta esse arquivo. O stdout redirecionado do injector
continua disponível como diagnóstico, mas não é uma confirmação confiável dentro
da janela curta de startup: ele pode permanecer em buffer.

Uma exceção durante `arm()` também fica registrada. O launcher preserva o estado
retornado pelo instalador, incluindo `failed-pinned`, sem tentar desfazer hooks.
O controle do endpoint usa `clean-map-endpoint-state.log` separado pelo mesmo
motivo. Esses registros contêm somente fase/erro/geração, nunca token ou rota.

Em 24/10, a primeira tentativa real carregou a bridge, mas o controlador recebeu
timeout sem confirmação. A inspeção passiva posterior encontrou os nove corpos
OEM originais intactos e o ponteiro nativo de bootstrap nulo. Isso não foi uma
instalação concluída nem prova de mapa secundário. Os testes de persistência
validam a gravação/flush antes da mensagem e a preservação de falhas pinned.

## Uso controlado

O gate Java de startup continua limitado a dez segundos. Depois de `armed` e da
liberação desse gate, o observador pode aguardar a primeira carga OEM por até
cinco minutos, permitindo a conexão manual do iPhone. Esse prazo não permite
instalação quente: os módulos precisam estar ausentes quando o observador é
armado, e os nove pontos só podem ser instalados no evento frio já revisado.

### Namespace Android 9

A tentativa real de 24/10 confirmou `RTLD_NOLOAD` recusando uma biblioteca OEM
já presente em `/proc/PID/maps`. Uma sonda sem hooks abriu e fechou as três
bibliotecas existentes usando o caller OEM no `__loader_dlopen`; nenhuma imagem
nova foi carregada. No mesmo dispositivo, `dladdr` não descreveu o linker.

A bridge agora oferece uma preparação adicional que aceita um endereço OEM já
atestado. Ela valida o módulo do caller, resolve o loader e exige que seu endereço
esteja no mapeamento RX exato de `/system/bin/linker64`. Mantém `RTLD_NOLOAD` para
os três módulos e usa o namespace próprio apenas para fixar a bridge. A assinatura
antiga e ABI 1 continuam disponíveis. O caminho de ativação de vídeo reaproveita
esses mesmos handles retidos, evitando uma segunda abertura no namespace errado.

Essa seleção de namespace pelo endereço do caller está documentada no código do
[libdl do Android 9](https://android.googlesource.com/platform/bionic/+/android-9.0.0_r1/libdl/libdl.cpp).
A sonda valida a abertura no dispositivo; a nova bridge compilada e o passthrough
com os nove hooks ainda exigem seus próprios resultados antes de afirmar sucesso.

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
