# Performance Considerations

Atualizado em: 2026-05-24

## Pontos Identificados

- `InstrumentProjector2` tem deduplicação de valores com `lastSentValues`.
- `batchEvaluateJs` agrupa sincronização inicial.
- `updateWarningUI` tem guard para evitar loop de warning.
- O frontend renderiza dentro de WebView no cluster, então DOM/CSS afetam fluidez diretamente.
- Heartbeat roda a cada 2 segundos no JS; watchdog Android checa a cada 5 segundos.

## Riscos de Performance

- `evaluateJavascript` frequente.
- `setInterval` sem cleanup no frontend.
- CSS com blur/filtros/shadows pesados em fullscreen.
- Layout shift de cards/gauges.
- Assets grandes inlined no HTML.
- Reload de WebView durante direção/projeção.

## Arquivos Relacionados

- `InstrumentProjector2.kt`
- `cluster-widgets/default/src/core/main.js`
- `cluster-widgets/default/src/styles/night.style.css`
- `cluster-widgets/default/src/core/components/`

## Recomendações

- Preferir updates por evento e deduplicados.
- Medir antes de adicionar animações contínuas.
- Usar classes CSS estáveis e dimensões fixas para componentes do cluster.
- Validar na central real para alterações visuais.

## A Confirmar

- Métricas aceitáveis de CPU/memória na central.
- Ferramenta padrão para profiling no ambiente do carro.
