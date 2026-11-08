# Persistência de layouts visuais

Atualização: 08/11/2026.

## Separação dos dados

| Coleção MongoDB | Responsabilidade |
|---|---|
| `flow_documents` | Versões imutáveis do JSON completo da skill, inclusive originais importados |
| `flow_drafts` | JSON IBM do rascunho editável e sua revisão |
| `flow_layouts` | Layout visual atual do fluxo, com revisão própria |

Salvar um layout não altera o JSON IBM, o rascunho ou as versões, não cria nós de diálogo e não envia chamadas IBM. O módulo `flow` fornece controller, service e repository próprios para o layout. O MySQL mantém os metadados de fluxo/projeto já existentes; não é necessária uma nova migration SQL.

O modelo é compatível com a futura adaptação ao React Flow, mas não é o objeto completo retornado por `toObject()`. Condições, conteúdo, conexões e demais dados semânticos permanecem no JSON IBM. Projeção IBM → diagrama e edição semântica pelo diagrama ainda precisam ser implementadas.

## Contrato HTTP

Todas as rotas exigem autenticação por sessão. Escritas exigem CSRF e permissão de edição (OWNER, EDITOR ou administrador global). OPERATOR e VIEWER têm leitura. Fluxos/projetos/portfólios arquivados permitem leitura e impedem gravação. Não membros recebem 404.

| Método | Rota | Resultado |
|---|---|---|
| GET | `/api/v1/flows/{id}/layout` | 200, corpo e ETag; 404 `LAYOUT_NOT_FOUND` se ainda não criado |
| PUT | `/api/v1/flows/{id}/layout` + `If-None-Match: *` | Cria layout, 201, revisão 1 |
| PUT | `/api/v1/flows/{id}/layout` + `If-Match: "layout-N"` | Substitui o layout inteiro, 200, revisão N+1 |

Resposta inclui `flowId`, `revision`, `schemaVersion`, `nodes`, `viewport`, `updatedBy` e `updatedAt` UTC. Ambas as rotas respondem com `Cache-Control: no-store`. O ETag visual (`"layout-1"`) não é intercambiável com o ETag do rascunho (`"1"`). Não há criação implícita no GET.

Corpo de criação/gravação:

```json
{
  "schemaVersion": 1,
  "nodes": [
    {"id": "grupo-visual", "position": {"x": 0, "y": 0}, "width": 600, "height": 400},
    {"id": "node_1", "position": {"x": 100, "y": 200}, "parentId": "grupo-visual", "collapsed": false}
  ],
  "viewport": {"x": 0, "y": 0, "zoom": 1}
}
```

`parentId` representa agrupamento visual, não modifica `parent` ou `previous_sibling` da IBM. IDs visuais são opacos: nesta etapa não é exigido que correspondam a elementos do JSON IBM. Grupos podem ser nós exclusivamente visuais, sem equivalente na skill. A futura projeção deve definir o mapeamento estável e o tratamento de posições órfãs quando o conteúdo mudar.

`width`/`height` são dimensões preferidas do layout, opcionais. Na integração frontend, use-as para definir estilos/tamanho do componente; não substitua medidas internas calculadas pelo React Flow. `collapsed` é estado visual, a ser interpretado pelo frontend. Posições de filhos devem seguir as coordenadas relativas ao grupo adotadas pelo editor. Referências: [Node](https://reactflow.dev/api-reference/types/node) e [tipos React Flow](https://reactflow.dev/api-reference/types).

## Validação e falhas

- `schemaVersion` obrigatório e igual a 1; `nodes` obrigatório, podendo ser vazio; viewport opcional.
- Até 5.000 nós e 1 MiB de JSON UTF-8 por requisição.
- IDs não vazios, sem caracteres de controle, com até 1.024 caracteres e sem duplicatas.
- Coordenadas finitas entre -10.000.000 e 10.000.000; dimensões opcionais maiores que zero e até 1.000.000; zoom finito entre 0,01 e 100.
- Grupos referenciados precisam existir no mesmo layout. Autorreferência e ciclos são recusados.
- Campos desconhecidos são recusados, inclusive `edges`, `data`, condições e o envelope completo do React Flow. Isso evita confundir persistência visual com edição semântica.
- Sem precondição: 428 `LAYOUT_PRECONDITION_REQUIRED`. ETag inválido/ambas as precondições: 400. Revisão antiga, atualização de layout inexistente ou criação concorrente: 412 `LAYOUT_REVISION_MISMATCH`.
- Erros de Mongo: 503 `LAYOUT_STORAGE_UNAVAILABLE`. Integridade/escopo incompatíveis do documento: 409 `LAYOUT_INTEGRITY_ERROR`.

Em uma falha de rede depois de salvar, consulte o layout antes de reenviar. Uma gravação concluída incrementa a revisão; repetir a revisão antiga retorna 412. Não há retry automático, merge automático, exclusão pública nem múltiplos layouts por usuário nesta entrega.

## Armazenamento e concorrência

Um documento por fluxo em `flow_layouts`, com `_id=flowId`, `portfolioId`, revisão, `rawJson`, checksum, autor e horário da última atualização. O payload visual é normalizado e salvo como string JSON. O índice único `_id` impede criação duplicada. Atualização usa `findOneAndUpdate` com escopo e revisão esperada no filtro, sem upsert.

O service verifica acesso SQL antes de acessar o Mongo, sem transação SQL aberta durante a gravação Mongo. Como nas outras operações entre bancos, não há transação distribuída com alterações simultâneas de autorização/arquivamento. A leitura confere escopo e hash antes de devolver o conteúdo.

O layout pertence ao fluxo corrente. Criar uma versão do JSON IBM não cria um snapshot do layout; reabrir uma versão antiga não restaura posições antigas automaticamente. Histórico visual por versão e sincronização conjunta entre conteúdo/layout são evoluções futuras explícitas.

## Uso no Bruno

Na pasta `05-fluxos`: `14-Criar-layout`, `15-Ler-layout` e `16-Salvar-layout`. As requisições capturam `layoutEtag` em memória, separado de `draftEtag`. Crie/import um fluxo antes. Para layout existente, comece pelo GET.

## Validação

Os testes do módulo cobrem HTTP, controle de revisão, preservação de JSON/rascunho, permissões, CSRF, arquivamento, escopo, integridade e validação do payload/grupos.

`LocalFlowLayoutTests` é opt-in e usa MySQL/MongoDB reais, sem chamadas IBM. Verifica gravação/leitura de grupos, CAS concorrente diretamente no Mongo para não mascarar a corrida com locks SQL, nova revisão e preservação do original/rascunho. Remove somente suas fixtures.

```powershell
./mvnw.cmd '-Dtest=LocalFlowLayoutTests' '-Dtabula.layout.local.verify=true' test
```

Resultado em 08/11/2026: suíte completa com 110 testes executados, zero falhas/erros e 13 casos opt-in pulados (123 registrados). A validação separada com MySQL/MongoDB reais passou, incluindo apenas uma gravação vencedora na disputa pela mesma revisão e limpeza das fixtures. Logs: `target/layout-full-suite.log` e `target/layout-real-persistence.log`. Os 73 arquivos Bruno (71 requisições, collection e ambiente exemplo) e os scripts passaram no parser oficial e na verificação sintática JavaScript; as rotas novas foram exercitadas via MockMvc, sem execução da collection pelo aplicativo Bruno.
