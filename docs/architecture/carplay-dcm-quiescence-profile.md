# Evidência de ausência no DCM da central

O preparo automático usa o serviço
`com.ts.car.dcm.dccontroller/.connectionmanagerservice.CmService` apenas para leitura.
A interpretação é específica do APK OEM de 237.975 bytes, SHA-256
`7859c63e089d6f053df7b4121063e30d4e5f7795cf8fca52a73bebe8a44b9b69`.

`CarPlayBridgeQuiescence` resolve esse serviço no PackageManager e verifica o arquivo
`ApplicationInfo.sourceDir` local. A aprovação fica em memória por identidade do
arquivo e época do Binder; nova conexão, caminho, tamanho ou data de modificação
exigem nova conferência. Falha de leitura ou firmware diferente retorna desconhecido.
Não há root, rede ou comando de recuperação nessa leitura.

## Semântica comprovada no APK

Na transação 14, `getOnlineDevices(Bundle)` inclui somente dispositivos para os
quais `Device.isOnline()` é verdadeiro. Esse método exige mapa de conexões não
vazio e estado do dispositivo diferente de 2, 3 e 4. Portanto, uma entrada online
não é simplesmente um telefone salvo. Há uma exceção estreita para registros
pareados retidos, comprovada também pela leitura física Bluetooth descrita abaixo.

Na transação 45, `getActiveProjections()` delega a
`ProjectionManager.getProjectionDevices()`:

| Resposta na wire | Significado no firmware verificado | Tratamento |
| --- | --- | --- |
| Lista nula, tamanho `-1` | Mapa de conexões de projeção vazio | Evidência de ausência |
| Lista não nula vazia, tamanho `0` | Mapa não vazio, mas sem dispositivo ativo retornado | Desconhecido; bloqueia |
| Lista com dispositivos válidos | Dispositivos de projeção observados | Ocupado; bloqueia |
| Erro, tamanho inválido ou bytes restantes | Resposta não comprovada | Desconhecido; bloqueia |

A lista vazia não prova inatividade: `makeProjectionConnection()` pode adicionar
uma conexão após `activateConnection()` apenas enfileirar o evento de preconexão,
antes de existir um dispositivo ativo. O leitor não admite essa janela.

O resultado final só afirma ausência com Bundle online presente, completamente
decodificado e vazio ou composto exclusivamente pelos registros retidos comprovados
abaixo, além da lista nula de projeções. Continuam obrigatórios
cinco segundos com o mesmo Binder e pelo menos três respostas consecutivas frescas
ao longo de dois segundos. Desde a revisão 137, o supervisor não consulta câmbio
nem velocidade; continua exigindo identidade OEM e ausência de telas de projeção. Não existe
neste SDK um sinal explícito de inicialização concluída; o tempo de espera é uma
política conservadora, não esse sinal.

## Registro pareado retido e prova Bluetooth independente

O DCM pode conservar um Android/iPhone aceito (`type=1/2`, `accepted=1`), estado
global OFFLINE `0` ou ONLINE retido `1` e mapa de conexão exatamente `{BT=2: READY=3}`, mesmo quando
não há telefone conectado. `BtConnection.Disconnected` e `Detecting` atribuem
READY também a dispositivos apenas pareados. `Device.updateDevice()` pode promover
READY a ONLINE e conservar esse estado após a desconexão dos perfis. O estado global OFFLINE, por sua vez,
força capacidades disponíveis/ativas para zero: esses zeros não provam ausência
física. Mapa de perfis vazio também não equivale a perfis desconectados.

Na versão 135, os estados globais `0/1` são apenas candidatos; nenhum prova ausência
física. Uma leitura real da 134 encontrou ONLINE `1`, BT READY, capacidades zero e
nenhuma projeção, enquanto o serviço Bluetooth confirmou os cinco perfis
desconectados e o agregado vazio antes/depois. Isso demonstrou o bloqueio indevido
da exigência anterior de OFFLINE. A classificação corrigida mantém a prova física
independente e todas as demais restrições.

No APK DCM de SHA-256 fixado acima, as evidências REA
`ev_ba63834a809a7e3f5641635dd8877830aa29ec1fcc02baaeb1dbceec116b2ded`
e `ev_9e04ff0d43eb5172bc6442995051481ee8ba723fbc5338282ca0746d798c273d`
mostram os caminhos que atribuem READY ao pareamento. A evidência
`ev_688baa0902118d2aa1b623f1936e57838a6a4c6143eaf1a4170f433ecc89bc94`
mostra a promoção a ONLINE e a retenção desse estado. Isso é evidência estática;
a ausência física foi confirmada separadamente pelas leituras Binder descritas.

Somente essa assinatura é candidata: capacidades disponível/ativa zero, mapa de
estados ativos vazio, endereço Bluetooth válido, modo USB device desativado e os
três nodes nativos USB HID/IAP/NCM ausentes. Qualquer outro tipo, estado, conexão,
node ou campo desconhecido bloqueia. Serial USB e MAC Wi-Fi históricos não são
usados como prova de conexão atual. Uma candidata ainda precisa da lista nula de
projeções DCM e da confirmação física independente.

`CarPlayBluetoothQuiescence` mantém um bind assíncrono, sem `BIND_AUTO_CREATE`, ao
serviço existente `com.ts.car.bluetooth.service/.BluetoothService`, interface
`ts.car.bluetooth.sdk.IBluetoothManager`. Não registra callbacks SDK, não liga o
rádio e não conecta/desconecta dispositivos. Verifica por época do Binder o APK
local de 2.871.765 bytes, SHA-256
`c99e49b47704e610c044ab01aa530ca451784e9e78885b11cad1c1f45518eac0`.

O protocolo foi conferido no serviço e no DEX inalterado do `ts-framework.jar`,
SHA-256 `33570de5adee6bd24bdaf0c5e3641210b299c4c2764ee0e47f2b8804ffb82943`:

- TX4 lê o estado do adaptador. Este método específico deixa o `CallResult` novo
  com código `0` e mensagem nula; esse comportamento só é admitido no APK verificado.
  Estado OFF `10` confirmado duas vezes prova rádio desligado; transições `11/13`
  e valores desconhecidos bloqueiam.
- Com estado ON `12`, TX12 fornece todos os endereços conhecidos pelo serviço,
  incluindo os adicionados durante eventos CONNECTING. A união desses endereços
  com os dos registros DCM candidatos deve ser não vazia e limitada a 16 itens.
- TX14 consulta cada endereço nos cinco perfis reais `0..4`: HFP, PBAP, A2DP,
  AVRCP e MAP. Exige `CallResult.SUCCESS=1` e estado DISCONNECTED `0` em todos.
  CONNECTING `1`, CONNECTED `2` e DISCONNECTING `3` bloqueiam. Proxy indisponível
  devolve estado zero com erro `2`, que continua desconhecido. PAN `5` é rejeitado
  por TX14 nesse firmware e não é uma sexta proxy válida.
- TX13 com perfil `-1` deve devolver lista não nula vazia antes e depois. Essa
  consulta é suplementar: o serviço pode devolver vazio com sucesso para um
  gerenciador ainda sem dados. TX12 deve manter o mesmo conjunto antes/depois e
  TX4 deve continuar ON.

Cada resposta exige `readException`, marcadores presentes, decode completo e zero
bytes restantes. TX12/13/14 exigem sucesso `1`; nunca se generaliza a exceção de
código `0` da TX4. Endereços e nomes ficam internos e não aparecem em logs/status.
Falha, troca de Binder, mudança de firmware, excesso de dispositivos ou leitura
acima de 1,5 segundo bloqueiam. O Binder Bluetooth também espera cinco segundos;
o resultado físico participa da janela DCM existente de dois segundos.

Há uma prova adicional de prontidão com rádio ON: `handleInitilize()` solicita as
cinco proxies e carrega o banco antes de finalizar. O listener publica as respostas
das proxies no mesmo Handler; a atribuição das proxies ocorre depois da mensagem
de inicialização. Assim, cinco consultas TX14 com sucesso provam proxies prontas
após a carga do banco, além do tempo conservador de espera.

Evidências REA Bluetooth: `ev_b4c1e24b2eb38fb27050734c7cc9a5083afca2cbbbdb35cc42e393ca7d67111b`
(proxy e erros), `ev_bcaa36a1f6eaa986ef29b78b7bd3578799f29e84804e69dfd05b67b550e32fc9`
(inclusão de dispositivos em conexão), `ev_78ce9dcf7f6f58e45ed0b4b24cd7af9d8768cffdc16acabcfb7d88f98fe38611`
(conjunto TX12), `ev_405490c5c2762d3e0173d4b94a4dd0ea011bbbc1112df3c3b8c6abb15b028c05`,
`ev_c1fa66122a2714e181e246003e57404eb84cadfd7cbddd72e6f4ec3e3f831cd6`
e `ev_2b5d4df99d2f286bc977f29115c06e9e8ebd668969b6c603d1b72fa6ab45eaec`
(ordem de inicialização).

## Registro de infraestrutura DCM/Wi-Fi da 146

No primeiro boot de campo da 146, a central não tinha projeção ativa e as sondas
visuais indicavam CarPlay e Android Auto ausentes, mas TX14 devolveu dois registros.
O primeiro tinha a assinatura `type=0`, `accepted=1`, `state=1`, conexão exatamente
`{3=3}`, capacidade disponível `4096`, capacidade ativa zero, nenhum estado ativo,
nenhum node USB e nenhum endereço Bluetooth válido. TX45 devolveu lista nula.
Esse registro é interno ao DCM e não é um telefone identificado.

A análise estática do APK DCM fixado confirma que tipo `0` é `UNKNOWN`, que o mapa
de conexão não vazio com estado global `1` basta para o OEM rotular um registro como
online, e que as capacidades de projeção reconhecidas pelo firmware ficam entre os
bits `8` e `2048`; `4096` não é mapeado como serviço de projeção. Evidências REA:
`ev_c4c4368de6b734a91563cdc2459f9d6f15572129ec3691287bdbd3075797e160`,
`ev_9ecf1acafc9787a5f92bedd88238d31d1385bfe36537438b5147a00e009f9d30`,
`ev_93f4e22f9fa1e1b1460adfb689831b6bc34565f8f8055062148dff0527988f57`
e `ev_a6f4f3fbbdc58bed80ddd656c765cd19e748258a918c96187a7baaf4fe9c680d`.
São observações estáticas do APK, não execução do firmware.

A 147 admite apenas essa assinatura exata como infraestrutura inativa. Qualquer
mudança de tipo, aceite, estado, conexão, capacidade, identidade Bluetooth, node
USB ou atividade volta a bloquear. A lista nula de projeções, as sondas visuais,
a janela temporal, a identidade do firmware e todos os demais guardas continuam
obrigatórios. O diagnóstico privado registra apenas a contagem ignorada.

## Evidências e limites

REA no APK capturado, sem executá-lo:

- `ev_b95ce38630def79164ace7bdfd7d8fc109f20ec084da9dc2982727b7a2928165`: delegação da transação 45.
- `ev_af62956ecfce58227b9e1cf0362518f3bfe330fbecdef166365363d1433b2ffc`: ramos lista nula e lista não nula.
- `ev_20169cd3b3f4ab42239d3d929ed3aff0f46643bfeeb395f470a3bbfc890655a2`: seleção de dispositivos online.
- `ev_d3c75a7e21d721b50e825447fa49055fd1f209d77afbc9b2466811f27b790491`: predicado `isOnline()`.
- `ev_341e01af88fd177c92a6f07299df3a319883e057643dc7bae81e0291ce21011a` e `ev_b47df538ee505f9a721ce61a155594f0662ba6f9b1ba54cca6906c6e3249ef34`: conexão registrada após enfileirar preconexão.

O APK release anterior também renomeou `ConnDevice` pelo R8, impedindo o Bundle
externo de localizar esse Parcelable pelo nome OEM. A regra de preservação de nome
é necessária para respostas não vazias; ela não autoriza ignorar falhas de decode.
Não registrar identificadores ou conteúdo dos dispositivos nas mensagens de diagnóstico.
