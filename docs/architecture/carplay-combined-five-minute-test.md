# Teste combinado CarPlay de cinco minutos

## Objetivo

A versão 74129 simplifica o ensaio de campo em Recursos > Teste CarPlay · captura + HUD.
Um único botão inicia uma sessão limitada a cinco minutos; durante a sessão, o mesmo botão
encerra e limpa o teste. Não há mais uma sequência separada para iniciar a captura e ligar o
HUD.

## O que o botão inicia

- captura privada dos callbacks de orientação CarPlay já existentes;
- seleção automática da fonte textual experimental para o HUD nativo;
- captura persistente dos eventos já emitidos pelo cluster, delimitada pelos eventos
  `carplay_combined_test_start` e `carplay_combined_test_stop` com o mesmo UUID da captura;
- temporizador monotônico de cinco minutos, além do encerramento manual.

Uma lease em memória mantém o logger do cluster ativo durante a sessão sem alterar a
preferência persistida do proprietário. Leases são independentes, portanto o fim do teste não
desabilita outro diagnóstico concorrente nem sobrescreve uma mudança feita durante o percurso.
Os registros de navegação continuam em armazenamento privado e os eventos do cluster seguem
o mecanismo existente de retenção. Não há envio automático pela rede.

## Segurança e isolamento

Android Auto mantém prioridade no HUD. A sessão combinada não modifica o publisher, o JSON,
os temas, a resolução, o handoff display 0/3 nem a Surface do Android Auto. Encerrar remove a
assinatura Binder da captura, retira a entrada CarPlay do HUD e cancela os temporizadores.
Falha de escrita ou incompatibilidade de Parcel também passa pelo mesmo encerramento.

O teste não inventa seta para códigos de manobra ainda desconhecidos. Com a bridge preparada,
o fluxo atual também habilita a tentativa experimental de vídeo secundário no cluster:
solicita um endpoint e tenta criar uma Surface dedicada, preservando a sessão principal.
A admissão exige CarPlay no D0, prioridade do Android Auto, disponibilidade do slot e ausência
de UI de segurança ou app concorrente. O experimento fica desligado fora do teste.
Bridge pronta, endpoint aceito e dados de rota recebidos não comprovam mapa renderizado;
uma ausência de mapa deve ser registrada como indisponibilidade, nunca como sucesso.

## Teste real da 145 em 27/10/2026

O proprietário confirmou Apple Maps. A sessão terminou automaticamente em 300035 ms,
com 265 callbacks, zero erros e zero perdas. Desses, 244 continham estado 1, rua,
distância e unidade m/km elegíveis para o texto experimental. O preparo automático
estava realmente READY no novo boot: nove hooks nativos pinned, Java armado, dois
loaders com exit 0 e provas de residência correspondentes à identidade OEM atual.

O endpoint foi aceito, mas o host registrou `POLICY_DENIED`, `consumer=none` antes
de criar a saída de vídeo. O registro agrega vários critérios; não identifica qual
falhou. O proprietário também informou ausência de texto no HUD, apesar de layout
AR, navegação habilitada e janela 4096 criada. A captura não registra a decisão do
árbitro HUD; recebimento de payload elegível não comprova publicação/renderização.

A 146 preliminar, apenas com a sincronização v9 (fonte `4cc520fd`), não altera esses
caminhos e não foi publicada. A 146 corrigida trata observações, espera e desenho
textual conforme [a arquitetura da correção](carplay-146-output-readiness.md);
a validação física continua pendente. Evidências privadas em
`test-artifacts/release-74146-v9-20261027/` no workspace original. A sessão completa
foi reconstruída de dois snapshots com 235 linhas de sobreposição exata e contagens
start/stop consistentes; a tentativa de obter o hash do arquivo histórico inteiro
expirou e não constitui uma cópia verificada desse arquivo completo.

## Procedimento de campo

1. Após instalar e reiniciar sem telefones, espere `Bridge preparada para esta sessão`.
2. Conecte o iPhone e inicie uma rota no Apple Maps. Em Recursos > Teste CarPlay,
   toque em `Iniciar teste CarPlay` e volte à tela do CarPlay na central.
3. Percorra até cinco minutos. O passageiro pode tocar em `Encerrar teste`; caso contrário,
   a sessão termina automaticamente ao completar cinco minutos.
4. Mantenha a central online ao final para recuperar os arquivos privados e o log do cluster.
