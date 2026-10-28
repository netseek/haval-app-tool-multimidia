# Controlador frio do processo CarPlay OEM

## Objetivo desta etapa

Criar uma janela determinística para instalar os nove hooks de passthrough antes
da carga de `libAirPlay_ts.so`, `libcarplaystack.so` e `libCoreUtils_ts.so`.
Esta etapa não anuncia um segundo display, não cria Surface no cluster e não
altera a Activity CarPlay do display 0.

## Evidência do ponto de espera

Análise estática do `TsCarPlayService.apk` embarcado, SHA-256
`0a44373f2acfa21b3c0d2af18eb8f67217398592eeca898015274afb7f030fb0`, mostrou:

- o manifest usa `com.ts.carplay.CarPlayApplication` e o processo exclusivo
  `com.ts.carplay` (REA `ev_cb7ab4f8...`);
- `CarPlayApplication.onCreate()` apenas chama o `Application` base e registra
  log (REA `ev_4068dd35...`);
- `CarPlayService.onCreate()` chama `super`, salva o contexto e só então chama
  `init()` (REA `ev_ccffe1a5...`);
- `init()` cria o `CarPlayManager` (REA `ev_82d4bdc3...`);
- `CarPlayNative` carrega `carplay_jni` somente no construtor, alcançado depois
  dessa criação (REA `ev_3a582f0c...`).

Isso sustenta um gate imediatamente depois de `Service.onCreate()` chamar o
`super` e antes de `init()`. É evidência estática do APK exato, não prova de que
o linker não antecipou nenhuma biblioteca no veículo; a primeira execução deve
confirmar `/proc/PID/maps` ainda sem as três imagens.

## Gate default-off

`patch_cold_start_gate.py` adiciona `ImpulseColdStartGate.awaitController()` no
ponto acima. O helper:

- retorna imediatamente quando
  `persist.haval.carplay.experimental_cold_hook` não contém exatamente 32
  caracteres hexadecimais minúsculos;
- quando ativado, espera pelo arquivo root-owned
  `release.<pid>.<nonce>` sob `/data/local/tmp/impulse-carplay/control`;
- cancela se a propriedade mudar;
- após 10 segundos continua o startup stock;
- não carrega código nativo, não altera SELinux, display, Surface ou SystemUI.

O nonce evita aceitar um arquivo de uma tentativa anterior. O diretório de
controle deve ser criado pelo controlador root com acesso restrito. O nonce não
substitui a autenticação posterior do canal app/OEM; ele apenas libera a janela
curta de startup.

## Launcher auto-armado

`make_cold_launcher.py` combina o interceptor revisado, o perfil v3 exato, o PID,
o hash/tamanho da bridge e o UUID principal
`e5f7a68d-7b0f-4305-984b-974f677a150b`. Entradas fora dos limites ou perfil cujo
digest canônico não seja
`31b878ba35c1a7fec07d7e854264837ef3702962a1e059fa5d020afa0d7c4d0f`
são recusados. O launcher publica somente mudanças de fase e contagem de hooks.

## Sequência do primeiro teste

1. CarPlay desconectado, sem Activity CarPlay em D0/D3 e veículo parado.
2. Preparar bridge, launcher, log e diretório de controle com hashes conferidos.
3. Gerar nonce novo, ativar a propriedade e recarregar somente `com.ts.carplay`.
4. Confirmar PID novo, cmdline, start ticks e ausência das três bibliotecas.
5. Anexar o injector ao PID exato e aguardar `armed`.
6. Criar o release file exato e aguardar `installed-pinned`.
7. Limpar propriedade, nonce e release file; manter o injector vivo.
8. Só então conectar o iPhone e validar o CarPlay principal no D0.

Timeout, perfil divergente, módulo antecipado ou falha de bootstrap são falhas
fechadas. Não há retry no mesmo processo depois de `failed-pinned`. A recuperação
exige inspeção e, se necessário, reinício manual somente do host OEM com o carro
parado. Nenhum passo reinicia SystemUI ou move a Activity entre displays.

## Estado de build

O artefato revisado foi incorporado como
`app/src/main/assets/carplay_patches/TsCarPlayService.apk`, SHA-256
`ad019ce091ef55bbf57f7e242ce9cdb647a8596f1e1f7d28d0c7f340957dba01`.
O gate permanece inerte por padrão. `build_cold_gate_service_patch.sh` aceita
somente o baseline auditado ou esse artefato exato. Para o asset incorporado,
ele decodifica de novo, prova semanticamente a chamada e sua ordem, verifica a
assinatura e gera um recibo byte a byte idêntico. Assim, `assembleDebug` e
`assembleRelease` não reaplicam nem reassinam o patch já revisado.

O caminho de reconstrução do baseline continua disponível no Mac Mini. Como o
APK OEM não contém `resources.arsc`, ele usa `--no-res` e preserva o manifest
binário original (`--copy-original`), substituindo somente o DEX antes da nova
assinatura.
