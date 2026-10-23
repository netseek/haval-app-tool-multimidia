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

O teste não inventa seta para códigos de manobra ainda desconhecidos. Também não simula mapa
limpo no cluster: os logs correlacionados servem para provar o estado real da ponte e do
cluster. O protótipo de vídeo secundário continua default-off e sem uma Surface de produção;
portanto uma ausência de mapa deve ser registrada como indisponibilidade, nunca como sucesso.

## Procedimento de campo

1. Abra Recursos > Teste CarPlay · cluster + HUD e toque em `Iniciar teste CarPlay`.
2. Conecte o CarPlay e inicie uma rota no Apple Maps ou Waze.
3. Percorra até cinco minutos. O passageiro pode tocar em `Encerrar teste`; caso contrário,
   a sessão termina automaticamente ao completar cinco minutos.
4. Mantenha a central online ao final para recuperar os arquivos privados e o log do cluster.
