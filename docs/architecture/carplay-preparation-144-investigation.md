# Investigação offline do preparo da 144 — 26/10/2026

## Resultado comprovado

A instalação da 144 e o reinício autorizado foram concluídos. No boot
`1c592b4f-d99c-438e-9127-63dd6a079193`, o processo do Impulse era `3345`.
Às 19:03:58 (UTC−3), o evento `carplay_preparation_wait` registrou:

```text
reason=CARPLAY_NOT_KNOWN_INACTIVE
quiescent=false
cp_visual=false aa_visual=false observation_age=fresh
```

O preparo permaneceu aguardando até a última coleta, às 19:07. Não houve uma
nova tentativa de injeção, runtime da 144 ou confirmação READY. A falha 142 e o
READY 137 persistidos nas preferências pertencem a outros boots.

O leitor de visibilidade corrigido na 144 concluiu sua leitura. A falha anterior
do AWK não explica este bloqueio. A preparação já não consulta P ou velocidade;
essas condições também não explicam o estado atual.

Após a coleta, o desligamento solicitado foi enviado uma vez pela GWM. A API
aceitou às 19:07:31 e uma atualização nova confirmou `hyEngSts=0` às 19:07:41.
Esta investigação não ligou, acessou nem modificou a central novamente.

## Onde a informação se perde

`CarPlayBridgeQuiescence.read()` combina três resultados diferentes num booleano:

1. Um registro online DCM não passa na assinatura estrita de pareamento retido.
   Isso inclui campos desconhecidos, capacidades, tipos de conexão ou nodes USB
   fora da assinatura. A rejeição não comprova presença física de um telefone.
2. O DCM devolve uma lista de projeções não vazia.
3. A prova Bluetooth observa um agregado conectado ou um dos cinco perfis em
   CONNECTING, CONNECTED ou DISCONNECTING.

Todos podem produzir `false`. O evento da 144 não identifica qual deles ocorreu.
A mensagem “A central informa uma conexão ou projeção ativa” é mais conclusiva
do que os dados permitem no primeiro caso. Há, portanto, uma imprecisão comprovada
no diagnóstico; a causa física do bloqueio deste boot continua em aberto.

Erros de Binder, firmware, Parcel e decodificação retornam `null`, não `false`.
A janela temporal só devolve `false` ao receber uma amostra negativa atual;
ela não perpetua um resultado negativo antigo por conta própria.

## Conferência do OEM

Revisão estática com REA no DCM de SHA-256
`7859c63e089d6f053df7b4121063e30d4e5f7795cf8fca52a73bebe8a44b9b69`:

- `Device.isOnline()` aceita mapa de conexões não vazio e estados fora de 2/3/4.
  Isso não equivale, por si só, a um perfil físico conectado agora.
- `ProjectionManager.getProjectionDevices()` retorna `null` quando o mapa de
  conexões está vazio; pode retornar lista vazia quando o mapa existe sem um
  dispositivo ativo. A distinção atual continua necessária.
- Os construtores OEM arquivados correspondem à ordem de leitura de `ConnDevice`.
  Não foi encontrada evidência de erro de ordem de campos que explique `false`.

No Bluetooth de SHA-256
`c99e49b47704e610c044ab01aa530ca451784e9e78885b11cad1c1f45518eac0`, o gerenciador
mantém cinco proxies de telefonia/mídia. Não há consulta GATT/BLE no leitor do
Impulse. Não há evidência atual que atribua o bloqueio às luzes BLE.

As capturas detalhadas da 133/134 demonstram registros pareados retidos com
projeções ausentes e perfis desconectados, mas são históricas. Elas comprovam a
possibilidade desse comportamento, sem estabelecer que se repetiu no boot 144.

## Plano de captura e protótipo offline

O roteiro para a próxima sessão registra os campos abaixo, conferindo os APKs
OEM e mantendo todas as condições atuais de liberação. Ele deve preferencialmente
usar o leitor já ligado aos serviços dentro do aplicativo, com motivos privados
distintos para os três ramos, sem criar um cliente de recuperação:

- DCM TX14: quantidade e campos numéricos/booleanos dos registros online;
- DCM TX45: distinção entre lista nula, vazia e não vazia;
- Bluetooth TX4/12/13/14: adaptador, contagens e cinco estados físicos por índice
  anônimo, com conferências antes/depois.

Nomes, MACs, seriais, identificadores e caminhos de nodes não devem ser publicados.
Endereços permanecem apenas em memória como argumentos das leituras de perfil.
A captura não deve chamar preparo, hooks CarPlay, conexão/desconexão, mudanças de
rádio, reinício ou comandos veiculares.

Um protótipo separado foi preparado e arquivado apenas na evidência privada, em
`prototype-not-approved/`. Passaram sintaxe JavaScript, fixtures de distinção das
respostas, rejeição de dados excedentes/códigos de erro e anonimização do snapshot.
O bundle ESM usou as dependências fixadas já existentes. Isso não é um build
Android e não muda a 144 instalada. Nenhum coletor foi incluído no código de produção.

**Protótipo não aprovado para execução na central.** A fixture não simula Android,
Frida e Binder reais. A revisão encontrou requisitos ainda não validados de
descarregamento antecipado e drenagem dos callbacks de `ServiceConnection`; um
timer não resolve esse ciclo de vida nem interrompe um Binder bloqueado. Os
bundles privados ficam como evidência histórica, sem instalador ou comando de
execução. O veículo está desligado e não deve ser acordado para essa coleta.

## Limite e próximo passo

Não há dados suficientes para afirmar qual dos três ramos bloqueou o boot 144.
Não foi alterado o critério de liberação e não foi criada uma versão especulativa.
Na próxima sessão autorizada, capturar os campos acima junto do evento do mesmo
boot permitirá separar conexão real, registro retido e estado OEM desconhecido.
Só então corrigir a classificação específica e validar o preparo real.

Evidência privada: workspace original, `test-artifacts/reboot-74144-20261026/` e
`test-artifacts/quiescence-investigation-144-20261026/`, incluindo recibo do
desligamento e registros REA completos. Não publicar os logs brutos.
