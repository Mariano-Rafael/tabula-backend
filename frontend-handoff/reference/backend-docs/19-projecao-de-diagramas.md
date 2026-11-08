# Projeção estrutural de diagramas

Atualização: 08/11/2026.

## Entrega

O backend projeta o JSON preservado da IBM em nós e conexões para uma futura interface React Flow. A projeção não é gravada de volta como JSON substituto, não executa expressões e não chama IBM. A edição limitada de nós standard do rascunho usa [um comando separado](21-edicao-semantica-dialog.md). O original continua em `flow_documents`, o editável em `flow_drafts`, e posições/grupos em `flow_layouts`.

| Método | Rota | Origem |
|---|---|---|
| GET | `/api/v1/flows/{id}/diagram` | Rascunho existente, sua revisão e checksum |
| GET | `/api/v1/versions/{id}/diagram` | Versão imutável READY e seu checksum |

As rotas exigem autenticação e permissão de leitura no portfólio. Não membros recebem 404. Layout inexistente usa posições iniciais determinísticas, sem gravá-las automaticamente. Falhas de armazenamento ou integridade não são tratadas como ausência de layout. Fluxos arquivados continuam legíveis. A resposta usa `Cache-Control: no-store`.

## Contrato

A resposta contém `schemaVersion=1`, `flowId`, `source` (DRAFT/VERSION), `versionId` ou `draftRevision`, `sourceChecksum`, `type`, `readOnly`, `semanticComplete=false`, `layoutRevision`, `viewport`, `nodes`, `edges` e `diagnostics`. `readOnly=false` indica que existe nó com suporte ao comando limitado de edição no rascunho. Não representa permissão do usuário nem edição de toda a semântica IBM.

Cada nó contém:

- `id`: identificador visual opaco, derivado por hash do ID IBM, com prefixo `dialog:` ou `action:`. É estável quando os elementos são reordenados e cabe no contrato de layout. Use este ID, não o ID IBM bruto, ao salvar posições.
- `type`: `ibmDialog` ou `ibmAction`, a registrar como componentes personalizados no frontend; `group` para grupos puramente visuais.
- `position`, `parentId` e `style`: organização visual. Dimensões salvas viram estilos; não sobrescrevem medidas internas do React Flow.
- `data`: label, ID IBM (`sourceId`), ponteiro JSON (`sourceRef`), tipo de elemento (`kind`), condição original, `editable`, `layoutOnly`, `collapsed` e o fragmento completo do elemento em `original`. `editable=true` somente em nós standard de rascunho Dialog com ID único; demais elementos e versões permanecem false.

`sourceRef` usa JSON Pointer dentro da revisão/versão indicada, por exemplo `/workspace/dialog_nodes/0`. O índice pode mudar ao reordenar o documento, embora o ID visual permaneça igual. Para elementos com ID ausente/duplicado, IDs de diagnóstico dependem da referência de origem e não têm essa garantia de estabilidade. Não use ponteiros antigos para editar outra revisão.

Fragmentos desconhecidos são mantidos em `data.original`; demais campos da skill permanecem no documento de origem e em sua exportação. O diagrama parcial não substitui esse documento. Textos, expressões, respostas e fragmentos devem ser tratados como dados pelo frontend, sem avaliação ou HTML bruto.

## Dialog

O projector reconhece `workspace.dialog_nodes`, com tipos `standard`, `folder`, `frame`, `slot`, `response_condition` e `event_handler` para identificação estrutural. Condições são expostas sem interpretação.

| Relação (`edge.data.relation`) | Origem IBM | Significado |
|---|---|---|
| PARENT | `parent` | Do pai IBM para o filho; relação estrutural, não execução incondicional |
| EVALUATION_ORDER | `previous_sibling` | Do irmão anterior para o seguinte; ordem de avaliação, não garantia de transição |
| JUMP | `next_step.behavior=jump_to` + `dialog_node` | Salto explícito; selector original mantido em `edge.data.selector` |

As arestas têm IDs determinísticos por relação/origem/destino, tipo visual `default` e a referência do campo IBM em `edge.data.sourceRef`. Não são inferidos caminhos a partir de condições, digressões, slots ou campos desconhecidos. Ciclos de saltos podem ser válidos e não são confundidos com ciclos inválidos de hierarquia/irmãos.

## Actions

A entrega reconhece `workspace.actions`, identifica elementos por `action` e mostra um nó por ação. Preserva o fragmento inteiro, mas não interpreta passos internos, expressões de roteamento ou subações. Cada ação recebe `ACTION_INTERNALS_NOT_PROJECTED`. Não cria arestas fictícias a partir da ordem do array. O suporte completo à projeção de passos será uma etapa posterior, com contratos específicos do export real.

## Layout

Posições e viewport salvos são sobrepostos aos IDs projetados. Sem posição salva, aplica uma grade inicial estável, calculada pelos IDs. Essa grade é apenas organização visual, não representa ordem de execução.

Um ID extra do layout que seja referenciado como `parentId` é materializado como grupo visual. Outros IDs extras são posições órfãs e geram `ORPHAN_LAYOUT_NODE`; não viram elementos IBM. Grupos aparecem antes dos filhos no array, conforme o [contrato React Flow](https://reactflow.dev/learn/layouting/sub-flows).

O `parentId` visual é independente de `parent` IBM. A hierarquia IBM é representada por arestas PARENT, sem alterar coordenadas relativas nem agrupamento visual do usuário. `collapsed` é exposto para o frontend, sem remover nós ou inventar execução.

O layout é sempre o atual do fluxo, inclusive na projeção de versões antigas. Conteúdo e layout são lidos separadamente; a resposta informa ambas as revisões e não promete snapshot atômico entre essas leituras. Histórico do layout por versão continua pendente.

## Diagnósticos e limites

Diagnósticos têm `severity` (INFO/WARNING/ERROR), `code` e `sourceRef`. Incluem elementos/IDs inválidos, IDs duplicados, referências ausentes/ambíguas, tipos desconhecidos, saltos não representados, irmãos com pais diferentes, sucessores duplicados, múltiplos primeiros irmãos e ciclos de pais/irmãos.

Referências ausentes ou ambíguas não geram arestas com endpoints inexistentes. Elementos não objeto são diagnosticados e não viram nós. O endpoint não corrige o documento original. O indicador `semanticComplete=false` é mantido mesmo sem erros estruturais, pois a semântica IBM completa ainda não é interpretada.

Até 5.000 elementos de origem são aceitos por projeção, além dos grupos do layout; acima disso retorna 413 `DIAGRAM_TOO_LARGE`, sem truncamento silencioso. Valem os limites de JSON/revisões/escopo do módulo de fluxos. Um rascunho ausente retorna `DRAFT_NOT_FOUND`; uma versão não READY não pode ser projetada.

Referências oficiais para estrutura IBM: [modificação de Dialog](https://cloud.ibm.com/docs/watson-assistant?topic=watson-assistant-api-dialog-modify) e [Dialog node](https://cloud.ibm.com/docs/apis/assistant-v1?code=node). O uso desses contratos de estrutura não implica chamada à API V1: a importação do Dialog associado ao Assistant continua pela V2.

## Próxima etapa

O GET do diagrama não aceita alterações. Já existe um [comando PATCH separado](21-edicao-semantica-dialog.md) para título, condição e textos literais existentes em nós standard, com revisão base e preservação dos demais campos. Criação/exclusão de nós e edição das conexões ainda precisam de seus comandos. Salvar apenas layout continua sem alterar conteúdo IBM.

A importação definitiva é uma operação normal do produto, iniciada pelo futuro frontend: criar portfólio, vincular uma instância IBM, criar projeto/destino e solicitar importação pela API Tabula. O backend consulta a API IBM da instância vinculada e mantém versão original e rascunho separado no MongoDB. Não há necessidade de escolher um portfólio fixo ou executar uma carga inicial manual para desenvolver a projeção.

A importação obtém o JSON pela API IBM da integração escolhida. Endpoint/credencial próprios por portfólio e clientes isolados foram implementados em [instâncias por portfólio](20-instancias-ibm-por-portfolio.md). Vínculos legados PROPERTIES permanecem compartilhados; novos vínculos DATABASE usam a conexão informada pelo usuário.

## Validação

Em 08/11/2026, a suíte completa passou com 118 testes executados e zero falhas/erros; 13 casos opt-in foram pulados (131 registrados). Foram acrescentados seis testes do projector e dois casos HTTP/serviço para escopo, revisões e integridade. Os casos cobrem estabilidade de IDs após reordenação, relações explícitas, referências ambíguas, ciclos estruturais, grupos visuais, elementos desconhecidos, Actions parciais e preservação do documento. Log: `target/diagram-full-suite.log`.

A collection tem 73 requisições. Os 75 arquivos Bruno, incluindo configuração e ambiente exemplo, e seus scripts passaram na validação sintática com o parser oficial. As rotas de diagrama foram exercitadas via MockMvc. Esta entrega não executou chamadas IBM nem criou importações permanentes.
