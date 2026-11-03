# PR 15 — atualização técnica até o preparo frio da 149

Este documento consolida o trabalho realizado depois do primeiro envio da branch
`feat/carplay-cluster-research-v9`. O objetivo continua sendo revisão técnica:
nenhuma publicação OTA, ativação remota ou afirmação de mapa limpo funcional no
cluster é feita por esta PR.

## Escopo efetivamente portado

- bootstrap local e opt-in da bridge, empacotado no APK como assets verificados;
- biblioteca ARM64 compilada pelo host autorizado, interceptor frio e agente Java ESM;
- preparo no boot, troca do APK, abertura do app e disponibilidade do Shizuku;
- política fail-closed para identidade OEM, visibilidade de CarPlay/Android Auto,
  quiescência DCM/Bluetooth, orçamento de tentativa, circuito de falha e residência;
- controlador e launcher com staging imutável, deadlines do gate OEM e recibos
  ligados a `boot_id + PID + starttime`;
- endpoint autenticado, Surface/decoder secundários e teste manual único de cinco minutos;
- captura Binder de orientação, arbitragem conservadora com Android Auto e texto
  experimental no HUD físico;
- correções observadas nas versões privadas 133–149, incluindo o preparo frio que
  não é mais vetado por uma linha DCM retida quando o processo OEM ainda está frio;
- preservação da sessão de overlays transparentes acionada por input físico no cluster.

Não foram portadas customizações pessoais de conectividade, identidade visual,
catálogo de temas ou correções exclusivas do pipeline Android Auto. As leituras de
Android Auto existentes nesta branch são somente gates passivos de prioridade e
segurança do experimento CarPlay.

## Evolução do problema

1. O protótipo inicial provou captura de callbacks Apple Maps e HUD textual, mas a
   bridge nativa ainda era externa e manual.
2. O primeiro bootstrap persistente passou a embutir payloads verificáveis e a
   preparar o processo OEM localmente, sem depender da VPS.
3. As tentativas reais revelaram janelas de identidade, saída rápida do injector,
   callbacks residentes, compatibilidade do leitor OEM, registros DCM retidos e
   diferenças entre reinício do Android e recriação apenas do processo OEM.
4. A 145 chegou a `READY` e capturou 265 callbacks Apple Maps. Isso provou o
   bootstrap e a recepção, mas não provou frame de mapa no cluster nem texto no HUD.
5. A 146 adicionou observações discriminantes e corrigiu admissão/renderer textual.
6. A 147 tratou uma assinatura exata de infraestrutura DCM/Wi-Fi inativa.
7. A 148 passou a orçar a tentativa por identidade completa do processo OEM, e não
   apenas por `boot_id`.
8. Uma captura sem telefone ainda apresentou no DCM capacidade 128 e estado 4.
   Esses valores também são válidos durante Android Auto USB real e, portanto, não
   podem ser ignorados globalmente.
9. A 149 separa `COLD_ATTACH` de `COLD_RESTART`: um processo comprovadamente frio,
   sem módulos OEM carregados e com amostra visual nova explicitamente limpa, pode
   armar o interceptor sem reiniciar o host e sem usar a linha DCM retida como veto.
   O caminho aquecido continua exigindo quiescência completa.

## Fluxo do bootstrap

### 1. Empacotamento reproduzível

O build gera `assets/carplay_bridge/` com:

- `libcarplay_video_bridge.so` ARM64;
- `cold-launcher-template.js`;
- `endpoint.js` e seu manifesto;
- `cpbridge-inject`, derivado de um injector Frida fixado;
- `manifest.json` com hashes, tamanhos, revisão, modo de política e diretório remoto.

O injector derivado preserva a inicialização Frida, mas omite a tentativa de
reescrever a política SELinux porque esta central já inicia em permissive. A
transformação só aceita o ELF, preimage, arquitetura e digest exatos. O binário
original usado por outros recursos do app permanece inalterado.

### 2. Observação e decisão

`CarPlayBridgeBootstrapManager` observa:

- identidade do processo `com.ts.carplay` (`boot_id`, PID e starttime);
- ausência/presença dos três módulos OEM e mapeamento da bridge;
- fase, donos, recibos e provas residentes daquela mesma identidade;
- tasks CarPlay/Android Auto por um parser POSIX limitado e fail-closed;
- quiescência DCM e Bluetooth para qualquer restart aquecido;
- revisão do payload, contador de tentativa e circuito de falha.

Uma leitura ausente, velha, ambígua, grande demais ou malformada nunca autoriza o
preparo. A opção permanece desligada por padrão e é controlada explicitamente na
tela do teste ou por receiver protegido por `android.permission.DUMP`.

### 3. Caminho frio direto (`COLD_ATTACH`)

O caminho novo só é admitido quando:

- a identidade OEM atual é exata e viva;
- os três módulos revisados ainda estão ausentes;
- a bridge não está mapeada;
- uma amostra ActivityManager recente prova CarPlay e Android Auto visualmente ausentes;
- o mesmo ticket/revisão/tentativa permanece válido.

Ele não envia sinal, não mata nem reinicia o processo OEM. A prova visual/fria é
refeita antes do launcher e antes da liberação do gate. O pacote de segurança inclui
`coldAttach=true`, identidade, ticket, timestamp e elegibilidade. O script root lê
um único pacote imutável e o launcher nativo verifica o mesmo ticket antes dos hooks.

### 4. Caminho aquecido (`COLD_RESTART`)

Se os módulos já carregaram, o preparo só pode renovar um host ocioso após:

- ausência visual explícita de ambas as projeções;
- DCM verificado sem projeção ativa;
- Bluetooth verificado sem perfis físicos conectando/conectados/desconectando;
- janela temporal estável;
- identidade e pacote de segurança conferidos imediatamente antes do sinal.

Estado ativo ou desconhecido continua bloqueando. A 149 não relaxa esse caminho.

### 5. Publicação e residência

Os nove hooks são instalados apenas na janela fria e preservam trampolins originais.
Agentes nativo e Java usam execução `eternalize`: o cliente externo pode terminar,
mas a prova exige desafio criado depois da saída, duas amostras frescas, binding
exato, ausência de `dispose`, nove replacements pinados e Java armado. Estado
`failed-pinned` jamais tenta unload, kill ou repetição automática.

O orçamento automático é de uma tentativa por identidade completa do processo.
Uma nova recriação OEM no mesmo kernel recebe orçamento próprio; o mesmo processo
não recebe tentativa automática infinita. O botão manual só limpa um circuito
quando não existe bridge/dono publicado.

## Separação entre preparo e teste

`READY` significa que passthrough e endpoint estão preparados. Não significa que
um segundo stream foi aceito ou que um frame apareceu no display 3. O teste de cinco
minutos continua manual e temporário:

1. conecta a captura Binder da navegação;
2. arma o HUD textual conservador;
3. adquire uma lease privada de diagnóstico;
4. pede um endpoint de mapa limpo;
5. cria Surface/decoder secundários somente se todas as políticas atuais permitirem;
6. revoga e aposenta a geração ao parar, expirar ou perder qualquer gate.

O stream principal nunca é substituído pelo secundário. Falha secundária aposenta
somente sua geração. Android Auto ativo sempre vence; desconhecido bloqueia.

## Invariantes preservadas

- nenhum `force-stop com.ts.carplay.app` em handoff ou bootstrap;
- nenhum restart automático do SystemUI;
- nenhuma alteração de resolução, `1920x720`, Surface principal ou layout do cluster;
- nenhum `VIDEO_FOCUS_CHANGE`/`REFRESH_RENDER` preventivo no preparo;
- nenhum hook quente em bibliotecas OEM já carregadas;
- nenhuma remoção automática de hooks publicados;
- nenhuma seta/manobra inferida a partir de código OEM desconhecido;
- nenhuma rua, rota, MAC, serial ou token adicionados à telemetria pública;
- nenhum arquivo OEM privado ou captura bruta incluído nesta PR;
- nenhum relaxamento do pipeline Android Auto.

## Evidência automatizada

O conjunto portado contém:

- modelos JVM de decisão, tentativa, janela DCM, prova residente e publication gate;
- fixtures portáteis do lifecycle do bootstrap e leitor visual;
- cenários Node do interceptor, endpoint e peer residente;
- validação de bundling ESM e versões fixadas do Java bridge;
- testes nativos de passthrough, dispatch, decoder/lease e isolamento de stream;
- trava estática dos APKs OEM e do contrato D0/D3;
- geração determinística do payload, com cada arquivo conferido por tamanho e SHA-256.

O build Android/Kotlin/JNI deve ocorrer exclusivamente no Mac Mini autorizado. O
resultado do build da branch desta PR deve ser registrado separadamente do build
privado da 149; uma release pessoal não constitui validação automática desta branch.

## Evidência física e lacunas

Já foi observado no carro:

- passthrough frio instalado com nove hooks pinados;
- agente Java armado;
- CarPlay principal preservado no display 0;
- bootstrap `READY` em uma sessão;
- 265 callbacks Apple Maps em cinco minutos, sem erro ou perda.

Ainda precisa ser comprovado para esta atualização:

- `READY` automático em cold boot normal com a lógica 149;
- ausência de repetição manual após recriação do processo OEM;
- oferta Binder aceita no novo boot;
- Surface/decoder secundários ativos;
- primeiro frame de mapa limpo no cluster;
- rua/distância efetivamente visíveis no HUD físico;
- teardown, reconexão, sono longo e prioridade Android Auto em testes separados.

Até essas provas, a formulação correta é “bootstrap e captura validados em etapas;
saída de mapa/HUD da revisão atual pendente de teste físico”.

## Procedimento de validação no carro

1. Instalar a build revisada com CarPlay e Android Auto desconectados.
2. Reiniciar completamente a central.
3. Abrir o Impulse e aguardar `Bridge preparada para esta sessão` sem usar retry.
4. Só então conectar o iPhone e abrir CarPlay no display principal.
5. Iniciar rota no Apple Maps.
6. Tocar em `Iniciar teste CarPlay` e retornar à tela do CarPlay.
7. Observar por até cinco minutos HUD e cluster, sem operar a tela pelo motorista.
8. Encerrar/aguardar timeout, confirmar CarPlay principal intacto e preservar os
   diagnósticos privados para análise.

Falha em qualquer etapa deve registrar o motivo mostrado pela UI e a fase exata;
não se deve “corrigir” reiniciando processos ou removendo hooks sem nova revisão.
