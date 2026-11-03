# Validação Android — 23/10/2026

## Resultado e limite

Passaram a conferência real do código OEM carregado e um teste ARM64 isolado na
central. A biblioteca experimental ainda não foi instalada como hook no serviço
OEM, não anunciou segundo display e não comprovou mapa limpo no cluster.

O usuário informou carro desligado, carregando e sem estar nele. Telemetria antes
e depois: P, 0 km/h, engine_state=0, atualização do app com idade de um segundo.
Não houve comando GWM de partida/desligamento nem alteração do carregamento.
Não estabelecemos conexão CarPlay com telefone. Serviço residente não foi tratado
como prova de telefone conectado; esse estado não foi consultado diretamente.

## Ambiente e código OEM real

- Android 9, API 28, processo ARM64 `/system/bin/app_process64`.
- Impulse instalado: 1.0.0.74.127-preview, versionCode 74127.
- Serviço com.ts.carplay PID 6495 e app OEM com.ts.carplay.app PID 6170.
- Ferramentas Frida existentes: 17.5.2. Nenhum servidor Frida novo foi iniciado.
- Três arquivos OEM atuais coincidiram com os hashes dos artefatos capturados.
- Leitura limitada dos segmentos RX por `/proc/6495/mem`, sem ptrace/injeção:
  três segmentos completos e treze corpos de função coincidiram com o perfil v3.
  O início do processo foi o mesmo antes/depois da leitura.

Segmentos verificados:

| Módulo | Bytes RX conferidos | SHA-256 do segmento |
| --- | --- | --- |
| libAirPlay_ts.so | 320880 | dbb00f167f7dd7e36c8575505d7685d4ab9d47d133f47dd64b220c7ed12ea6d8 |
| libcarplaystack.so | 146960 | 624904258227550d25292cf6d33396369bd09167fe49294e114986189d4d1d96 |
| libCoreUtils_ts.so | 1365632 | f7e86846ffe8c6c122e3a2ef13b08d8731d4c019cc242f112c366f4e9357afcb |

Essa conferência utilizou as permissões e caminhos reais de `/proc/maps`; não foi
execução de `inspect()` do instalador dentro do processo OEM. O serviço já estava
com os módulos carregados e, por projeto, não é elegível à instalação inicial fria.

## Compilação interna autorizada no Mini

Fonte imutável: 9ceda01bac59c0b9d26b74f08ed4bdb94955ce07. Digest dos controles
auditados: 2a5246d117daef016b4bf08d06883b7b0f80de7cdceeb4f58491a654b5f55819.
Job: haval-74126-work-9ceda01bac59c0b9-20261023T124407Z-5277.
Scanner: 1063 arquivos. Build Debug/Java/Kotlin/JNI/ARM64 concluído em 2m14s,
47 tarefas. Suíte JVM: 837 testes em 93 arquivos XML, zero falhas/erros, um skip
do gerador de seed de radar sem RADAR_SEED_OUT. Não houve build Android na VPS.

Biblioteca ELF64 AArch64: 5477688 bytes, SHA-256
e7c12e3b520a704aa3dc81ae9cc2e35965896b1d7c9890407b690af80db66223.
Hash no Mini, na VPS e após transferência à central iguais.
Dependências: libmediandk, libandroid, libdl, libm e libc; runtime C++ estático.

O APK Debug interno não foi instalado nem publicado. Continua sem a biblioteca
nativa empacotada e é byte a byte igual ao Debug anterior de validação:
8fd85d22552d39a757aa936d3063b500994e378e5d03b4be5633e4e4f825a125.
Manifest também igual: a2565b90f5bb8bda888b424c18759c3a73a2e9db596ac84f65a24cdce1360e30.
Não houve mudança de versão, assinatura de produção ou OTA.

## Teste real isolado no Android

Probe JavaScript fonte 32a030d8, `android_isolated_smoke.js`, com PID e arquivo/hash
explícitos. Executado pelo fridainjector existente num `/system/bin/sleep` criado
para o teste, PID 1874, com expiração natural de 35 segundos. O probe recusa
qualquer comando diferente de sleep e qualquer presença dos três módulos OEM.
Não foi anexado Frida ao processo com.ts.carplay.

Verificações que passaram:

1. Carga da biblioteca nova e ABI de bootstrap 1, estado não instalado.
2. Preparação sem módulos OEM recusada, sem carregá-los implicitamente.
3. Bootstrap com argumentos nulos recusado e liberação de preparação vazia aceita.
4. Registro de module observer com callbacks para módulos existentes.
5. Geração de uma função ARM64 sintética com literal PC-relative, retorno 1.
6. replaceFast com retorno substituto 2 e chamada ao trampolim original retornando 1.
7. Revert/flush restaurando retorno 1, sem bootstrap ativo ou módulos OEM carregados.

Isso comprova execução dessas partes no Android/ARM64 real e compatibilidade básica
com o Frida 17.5.2. Não comprova os nove prólogos OEM, eventos síncronos de novo
carregamento dos módulos, sucesso de pinning no namespace OEM, ciclo de vida OEM,
autorização Unix no Android, Binder/Surface/MediaCodec ou negociação do iPhone.

## Transporte e limpeza

O download HTTP de porta temporária expirou; a tentativa ADB não estava autorizada
e foi desconectada, sem alterar chaves/configuração ou reiniciar o servidor ADB.
Transferências Base64 pelo Telnet foram rejeitadas pelo hash: IUCLC convertia o
conteúdo para minúsculas. A reprodução local dessa conversão gerou exatamente os
hashes incorretos recebidos, confirmando a causa. Arquivos rejeitados não foram
carregados e foram removidos.

O envio validado usou gzip e hexadecimal minúsculo por receptor não interativo,
com linha limitada, marcador de fim, tamanho e SHA-256 obrigatórios. Isso preservou
os bytes sem modificar a configuração global de segurança da central.

Pós-teste: o processo descartável terminou, os arquivos/diretório de teste foram
removidos, versão 74127 e início do processo OEM permaneceram iguais. O mapa de
memória OEM não continha frida/gadget/libcarplay_video_bridge. Apenas artefatos e
recibos privados na VPS foram mantidos.

## Evidência privada

- Coordenador: `test-artifacts/carplay-android-preflight/20261023T123756Z/`:
  preflight.json, oem-readonly.json, compatibility.json, loaded-code-attestation.json,
  isolated-android-smoke.json, postcheck.json e final-parked-state.json.
- Tentativas de transferência falhas preservadas em recibos separados.
- Build/ELF: `artifacts/carplay-android-9ceda01b/`; checksums do fetch verificados.
- Driver temporário de envio: `/tmp/run-isolated-android-hex.py`, com funções de
  Telnet selecionadas por AST do helper antigo, sem executar seu fluxo de potência.
  Não executar o helper antigo completo: ele contém desligamento no finally.

## Próximo teste e decisão sobre APK

Primeiro validar a interceptação fria no processo OEM somente em passthrough,
sem anunciar segundo display: startup, sessão principal, finalização e reconexão.
É necessária uma janela controlada antes dos módulos carregarem; simplesmente
conectar um telefone agora não cria essa condição. Não houve reinício forçado
do serviço nem do carro para tentar contorná-la.

Depois integrar a provisão autenticada app/UID/token/canal, transporte real de
Surface, host exclusivo do cluster com prioridade AA/segurança e saída de vídeo
independente. Anunciar o display secundário somente com esses recursos prontos.

O usuário autorizou gerar uma versão nova se útil para testar CarPlay. Ainda não
há um caminho de ativação no app: a biblioteca continua excluída e os consumidores
experimentais estão em internalDebug sem caller de produção. Portanto não foi
gerada uma release que sugerisse mapa limpo funcional. Quando houver um teste
funcional útil, usar um único APK conjunto com as mudanças v9 já integradas,
verificar o próximo versionCode livre e a mesma assinatura da .117 no Mini.
