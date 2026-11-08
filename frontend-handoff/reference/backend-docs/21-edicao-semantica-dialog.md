# Edição semântica de nós Dialog — primeira etapa

Atualização: 08/11/2026.

## Escopo

O backend permite editar título, condição e textos literais de respostas existentes de um nó Dialog `standard`, no rascunho local. A alteração atua sobre uma cópia completa do JSON preservado, com controle de revisão e uma única gravação CAS no MongoDB. Não modifica versões imutáveis, layout, IDs, hierarquia ou instância IBM. Criação de versão e aplicação no Draft IBM continuam sendo etapas explícitas separadas.

Ainda não cria/exclui nós, respostas ou variações; não altera relações/ordem/saltos; não edita Actions, nós complexos, mídia ou expressões de texto. Campos desconhecidos continuam preservados. Não é edição arbitrária de JSON Pointer nem substituição do documento por um diagrama parcial.

## Contrato

```http
PATCH /api/v1/flows/{flowId}/draft/dialog-nodes
Content-Type: application/json
If-Match: "1"
X-CSRF-TOKEN: <token>
```

```json
{
  "nodeId": "node_1",
  "title": "Atendimento inicial",
  "conditions": "#saudacao",
  "textEdits": [
    {"responseIndex": 0, "valueIndex": 0, "text": "Olá! Como posso ajudar?"}
  ]
}
```

`nodeId` é o ID IBM (`data.sourceId` do diagrama), não o ID visual hash de `nodes[].id`. O ID deve identificar exatamente um nó no rascunho. Não há edição por índice de nó; reordenação de nós não muda o alvo do comando.

`title`, `conditions` e `textEdits` são opcionais, mas ao menos uma alteração deve ser fornecida. Campos omitidos permanecem intactos. `null`, propriedades desconhecidas e um comando vazio são recusados. PATCH é um comando próprio do Tabula, não JSON Merge Patch.

`responseIndex` aponta para `node.output.generic[responseIndex]`; `valueIndex` aponta para `values[valueIndex]` dessa resposta. A resposta precisa ser `response_type=text` e o valor precisa conter `text` literal, sem `text_expression`. Não adiciona conversão de expressão para texto. Todos os demais campos do valor/resposta/output permanecem como estavam, incluindo `selection_policy`, `delimiter`, campos futuros, respostas de imagem e outras variações.

Resposta 200: JSON completo do rascunho atualizado, ETag da revisão nova (`"2"`) e `Cache-Control: no-store`. O ETag é o do rascunho IBM, independente de `layout-N`. Mesmo uma gravação com valor igual incrementa revisão. Repetir a revisão anterior retorna 412; em retorno incerto, consulte o rascunho antes de reenviar.

## Validação e autorização

- Sessão autenticada, CSRF e permissão OWNER/EDITOR/admin. VIEWER/OPERATOR não editam conteúdo. Não membros recebem 404.
- Fluxo deve ser DIALOG e ter rascunho. Arquivamento de fluxo/projeto/portfólio impede edição.
- `If-Match` obrigatório e no formato do rascunho; ausência 428, inválido 400, revisão antiga 412.
- Apenas nós `standard` (incluindo tipo omitido) com ID único são editáveis. ID inexistente retorna 404 `DIALOG_NODE_NOT_FOUND`; ID duplicado retorna 409 `DIALOG_NODE_AMBIGUOUS`.
- ID de até 1.024 caracteres, não vazio e sem controles. Título não vazio, até 512 caracteres, com letras/números Unicode, espaço, `_`, `-` e `.`. Condição não vazia, até 2.048 caracteres e sem caracteres de controle.
- Limites locais: payload de comando até 128 KiB, até 100 edições de texto, até 10.000 caracteres por texto. Textos podem ser vazios e conter Markdown, quebras de linha e tabulação; outros controles são recusados. Esses são limites do contrato Tabula, não declaração de limites globais IBM.
- Índices são inteiros não negativos e devem existir. Índices fracionários, alterações repetidas do mesmo par ou campos desconhecidos são recusados.
- Fluxo Action, nó complexo ou formato de resposta não suportado retorna 422 com código específico (`DIALOG_EDIT_REQUIRED`, `DIALOG_NODE_EDIT_UNSUPPORTED`, `TEXT_RESPONSE_UNSUPPORTED`, `TEXT_RESPONSE_INDEX_INVALID`). Campos inválidos retornam 400 `INVALID_DIALOG_EDIT`; pares repetidos retornam 400 `DUPLICATE_TEXT_EDIT`.

Não interpreta/evalua condições, valida toda a linguagem IBM ou executa conversas. O frontend deve renderizar os textos/expressões como dados; validação remota continua necessária na aplicação à IBM. Não é um validador completo de todas as referências existentes no fluxo.

## Consistência e preservação

O service autoriza, lê o rascunho com verificação de integridade, confere a revisão, modifica somente os campos solicitados em memória e chama a atualização completa existente com a mesma revisão. Se qualquer item do comando falhar, nenhuma alteração é persistida. A gravação Mongo filtra fluxo, portfólio e revisão; duas edições na mesma revisão não podem sobrescrever uma à outra.

Objetos são lidos com inteiros/decimais de precisão arbitrária. A reserialização pode mudar espaços/formatação, mas preserva estruturalmente campos desconhecidos, arrays e precisão numérica fora das alterações pedidas. O JSON original da versão importada não é sobrescrito. Layout não ganha nova revisão por editar conteúdo.

## Diagrama e Bruno

O diagrama DRAFT sinaliza `data.editable=true` para nós standard com ID único. A resposta tem `readOnly=false` quando existe ao menos um nó com esse suporte; versões imutáveis, Actions, grupos visuais e nós não suportados permanecem somente leitura. Esses indicadores descrevem capacidade do formato, não autorização do usuário: um VIEWER pode consultar a projeção, mas a API continua recusando sua escrita.

O indicador não autoriza editar todos os campos do nó: nesta etapa somente o comando descrito acima existe. `semanticComplete=false` permanece, pois o backend não implementa a semântica IBM completa. Após salvar, recarregue a projeção ou atualize a revisão de conteúdo conhecida; não envie uma nova edição com ETag antigo.

No Bruno, `05-fluxos/19-Editar-no-Dialog` usa `dialogNodeId` e `draftEtag`, capturando o novo ETag da resposta. Preencha o ID IBM e os índices reais do nó; remova título/condição/textEdits do exemplo quando não desejar alterá-los. A requisição não aplica nada na IBM.

## Validação

Os testes do módulo exercitam HTTP, ETag, autorização/CSRF, arquivamento, campos/formatos não suportados, IDs duplicados, preservação de desconhecidos e decimais extensos, edição sem gravação parcial e proteção do original/layout. A projeção é testada com seus indicadores atualizados.

`LocalFlowLayoutTests` foi ampliado para edição semântica com MySQL/MongoDB reais, usando conteúdo fictício e sem chamadas IBM. Verifica preservação, revisão e duas edições concorrentes na mesma revisão, com apenas uma vencedora. Remove somente suas fixtures.

Resultado em 08/11/2026: suíte completa aprovada com 133 testes executados, zero falhas/erros e 14 casos opt-in pulados (147 registrados). A validação separada com bancos reais passou, incluindo a edição concorrente e limpeza das fixtures. Logs: `target/dialog-edit-full-suite.log` e `target/dialog-edit-real-persistence.log`. A collection tem 77 requisições; os 79 arquivos Bruno e os scripts passaram no parser oficial/verificação sintática. A rota foi exercitada por MockMvc; nenhuma chamada IBM real foi realizada nesta entrega.
