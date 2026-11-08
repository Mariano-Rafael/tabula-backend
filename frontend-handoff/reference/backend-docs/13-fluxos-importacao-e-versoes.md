# Fluxos — importação, versões e rascunhos

Atualização: 2026-11-08. Esta página descreve os contratos implementados; validação com bancos reais deve ser distinguida dos testes isolados.

## Escopo

O módulo MVC `flow` permite importar Dialogs e Actions da IBM, receber uma skill JSON fornecida pelo cliente, consultar fluxos e versões, exportar snapshots locais e editar um rascunho com controle de revisão. Não publica conteúdo na IBM, executa conversas nem gera diagramas.

O MySQL mantém metadados e estados; o MongoDB guarda o JSON. O módulo usa contratos de `portfolio`, `identity` e `integration`, sem compartilhar seus repositories.

## Exportação IBM e formato salvo

Para o assistant atual, usa-se `GET /v2/assistants/{assistant_id}/skills_export`, com o assistant e a skill já cadastrados no destino. A exportação retorna todas as skills e o backend seleciona exatamente a skill cujo `skill_id` e tipo correspondem ao fluxo solicitado.

A primeira chamada pode iniciar processamento assíncrono. O Tabula retorna 202 com `code=IBM_EXPORT_PENDING` e `Retry-After: 2`, sem criar uma versão incompleta. O cliente repete o POST de importação com o mesmo corpo e Idempotency-Key. O backend não bloqueia uma requisição HTTP aguardando polling remoto.

Essa API exporta o **rascunho** do assistant. Destinos LIVE são recusados para exportação v2, evitando registrar uma versão de rascunho como se fosse conteúdo publicado. Exportação de releases terá contrato próprio posteriormente.

Um destino com workspace v1 e sem skill Dialog v2 pode usar `GET /v1/workspaces/{workspace_id}?export=true`; o conteúdo é envolvido em uma estrutura de skill local. Isso não exige confundir workspaceId com skillId.

O snapshot guarda uma skill completa, incluindo seu `workspace` e campos desconhecidos presentes nessa skill. Não é backup do assistant completo: outras skills e o envelope global `assistant_state` não integram esse fluxo individual. A futura publicação precisará de seu próprio contrato de composição e validação.

Exemplo mínimo de conteúdo de Dialog:

```json
{"type":"dialog","workspace":{"dialog_nodes":[]}}
```

Exemplo mínimo de Actions:

```json
{"type":"action","workspace":{"actions":[]}}
```

A validação inicial confirma tipo, objeto workspace, lista correspondente, tamanho e JSON válido. Não valida toda a semântica IBM, condições, saltos ou compatibilidade com o runtime futuro.

## Persistência

A migration `V4__create_flows.sql` cria:

| Tabela MySQL | Dados |
|---|---|
| `flows` | Projeto, nome, tipo DIALOG/ACTION, arquivamento e versão JPA |
| `flow_versions` | Número por fluxo, documentId, checksum, estado, origem, destino de origem, autor, data UTC e chave/hash de idempotência |
| `flow_write_outbox` | Versão reservada, estado da gravação e prazo da tentativa |

Coleções MongoDB:

- `flow_documents`: documento imutável por versão, com portfolioId, projectId, flowId, versionId, schemaVersion, checksum e rawJson.
- `flow_drafts`: um rascunho por flowId, com portfolioId, revisão, checksum e rawJson.
- `flow_layouts`: layout visual atual por flowId, independente do JSON IBM, com revisão, posições, grupos e viewport. Consulte [o contrato de layouts](18-layouts-visuais-react-flow.md).

`rawJson` é uma string JSON preservada dentro do documento MongoDB. Isso mantém chaves desconhecidas, inclusive pontos e `$`, sem conversão para campos BSON ou coerção numérica. Metadados são campos BSON indexados; consultas semânticas dentro do fluxo poderão usar projeções específicas posteriormente.

Na entrada manual, o texto JSON original é preservado. Na importação IBM, o transporte lê inteiros/decimais com precisão arbitrária e serializa a skill selecionada. Não há garantia de espaços ou ordenação byte a byte iguais à resposta HTTP original. Campos desconhecidos da skill não são descartados.

Checksum SHA-256 usa representação interna com chaves de objetos ordenadas, ordem dos arrays preservada e números com precisão arbitrária. É uma convenção do Tabula, não implementação completa do padrão JCS. O limite é de 4 MiB de UTF-8 por skill e profundidade máxima de 64 níveis. A resposta de exportação IBM pode ter até 8 MiB, pois inclui outras skills; consultas de metadados continuam limitadas a 1 MiB.

Nenhuma chave da conexão IBM ou MongoDB é adicionada ao payload. O JSON pode conter dados de negócio e deve ser acessado somente por usuários autorizados; ele não é escrito em logs ou mensagens de erro.

## Estados e consistência

1. Após obter e validar o conteúdo, reservar FlowVersion PENDING e outbox PENDING na mesma transação MySQL.
2. Gravar o documento MongoDB com ID estável, sem sobrescrever um snapshot existente.
3. Confirmar a versão como READY e a outbox como DONE em nova transação MySQL.
4. Falha de armazenamento identificada marca FAILED quando o MySQL está disponível. Falha na confirmação deixa a reserva reconciliável.

As chamadas IBM e MongoDB acontecem fora de transações MySQL. Não há transação distribuída nem garantia de exactly-once entre os três sistemas. Numeração e reserva idempotente são serializadas pelo bloqueio do projeto/portfólio já usado pelos serviços de autorização.

Somente READY pode ser exportado ou usado para inicializar um rascunho. A leitura verifica IDs de escopo, checksum registrado e checksum do JSON efetivo. Um documento ausente ou incompatível não é devolvido como versão válida.

`POST /versions/{id}/reconcile` permite concluir uma reserva quando o documento já existe e sua integridade confere. Isso também pode recuperar uma gravação cujo retorno foi incerto e foi marcada FAILED. Se o documento não existir, a reserva só pode ser marcada FAILED após o prazo de dois minutos; antes disso retorna 409 para evitar interferir em uma gravação em andamento.

A outbox desta entrega registra a intenção e a confirmação; **não há worker automático de retentativa**. Seu payload não contém uma segunda cópia do JSON no MySQL. Se o processo morrer antes de persistir o conteúdo MongoDB, a reconciliação identifica ausência e não consegue reconstruir o conteúdo perdido. Uma nova captura exige nova chave. Para reimportar da IBM no mesmo fluxo, informar flowId. A automatização de reconciliação e tentativas é uma evolução posterior.

## Permissões

OWNER, EDITOR e administrador global podem criar/importar/editar fluxos, rascunhos e versões em projetos ativos. OPERATOR e VIEWER têm leitura. Não membros recebem 404. Comandos exigem CSRF.

A importação IBM exige ainda destino ativo, integração ativa, vínculo com o mesmo projeto e portfólio, e skill ou workspace correspondente configurado. A delegação inicial da credencial a um portfólio continua exclusiva do administrador global; importar um fluxo não permite escolher outra URL ou credencial.

Fluxos, projetos ou portfólios arquivados impedem alterações e reconciliação administrativa por estas rotas. Versões prontas continuam consultáveis por usuários autorizados. Arquivar não exclui documentos ou snapshots; restauração e exclusão definitiva não estão implementadas.

## Rotas implementadas

| Método | Rota | Finalidade |
|---|---|---|
| POST | `/api/v1/projects/{id}/imports` | Importar skill IBM e criar snapshot |
| POST | `/api/v1/projects/{id}/flows` | Criar fluxo e snapshot com JSON fornecido |
| GET | `/api/v1/projects/{id}/flows` | Listagem paginada |
| GET | `/api/v1/flows/{id}` | Metadados do fluxo |
| PATCH | `/api/v1/flows/{id}` | Renomear com `name` |
| DELETE | `/api/v1/flows/{id}` | Arquivar |
| GET | `/api/v1/flows/{id}/versions` | Listar versões paginadas |
| GET | `/api/v1/versions/{id}` | Metadados e estado da versão |
| GET | `/api/v1/versions/{id}/export` | JSON do snapshot READY como attachment |
| POST | `/api/v1/versions/{id}/reconcile` | Conferir gravação incompleta |
| POST | `/api/v1/flows/{id}/draft` | Inicializar rascunho a partir de `versionId` |
| GET | `/api/v1/flows/{id}/draft` | JSON do rascunho com ETag |
| PUT | `/api/v1/flows/{id}/draft` | Substituir JSON bruto, exigindo If-Match |
| POST | `/api/v1/flows/{id}/versions` | Snapshot imutável do rascunho, exigindo If-Match |

Listagens usam page a partir de zero, size de 1 a 100 (padrão 20) e envelope comum de paginação. Fluxos arquivados são omitidos por padrão; `includeArchived=true` os inclui. Versões incluem os estados PENDING e FAILED para acompanhamento, sem expor conteúdo indisponível.

## Corpos e idempotência

Importação IBM:

```json
{
  "targetId":"53fd414a-1a67-43e7-9000-9131b79e18e8",
  "type":"DIALOG",
  "name":"Atendimento",
  "flowId":null
}
```

`flowId` é opcional: sem ele, cria um fluxo; com ele, cria nova versão de um fluxo do mesmo projeto e tipo. O nome é opcional na importação e pode vir da skill. O destino é um UUID local, não o assistantId IBM.

Criação com JSON fornecido pelo cliente:

```json
{
  "name":"Atendimento",
  "type":"DIALOG",
  "json":"{\"type\":\"dialog\",\"workspace\":{\"dialog_nodes\":[]}}"
}
```

Os dois POSTs e a criação de versão de rascunho exigem `Idempotency-Key` com 8–100 caracteres de letras/números ASCII, ponto, hífen, underscore ou dois-pontos. O escopo da chave é o projeto. Repetir a mesma requisição retorna a versão original; reutilizar a chave com outra operação ou conteúdo retorna 409. A chave não dispara nova gravação de uma versão FAILED: consultar/reconciliar a tentativa ou usar nova chave.

Uma versão pronta retorna 201 com Location; replay pronto também retorna 201. Replay PENDING retorna 202; FAILED retorna 409 com os metadados da versão. A fase remota IBM_EXPORT_PENDING retorna 202 com Retry-After, mas ainda não reserva uma versão local nem congela o conteúdo da IBM.

## Revisão do rascunho

Inicializar com `POST /flows/{id}/draft` e `{"versionId":"..."}`. Essa operação não sobrescreve um rascunho existente. A resposta e o GET retornam o JSON bruto e um ETag como `"1"`.

Enviar esse valor em `If-Match` no PUT do rascunho; o corpo é o JSON da skill, não um envelope com propriedade json. O MongoDB aplica compare-and-set por flowId, portfolioId e revisão; sucesso incrementa a revisão. Header ausente retorna 428; revisão divergente retorna 412.

Para criar snapshot, enviar `POST /flows/{id}/versions` com If-Match e Idempotency-Key, sem corpo. A versão captura o rascunho lido nessa revisão. Uma edição posterior não muda a versão anterior.

## Validação e operação local

Os testes isolados usam H2 e armazenamento de documentos simulado, cobrindo preservação do JSON, acesso, estados, idempotência, ETag, snapshots e reconciliação. Testes do gateway IBM cobrem seleção da skill, estado assíncrono, exportação v1 e recusa de destino LIVE.

O teste opt-in `LocalFlowImportTests` verifica ping MongoDB, migrations MySQL, exportações IBM de Dialogs e Actions, snapshots e rascunhos reais. Cria somente registros temporários com IDs próprios e os remove dos dois bancos ao finalizar, preservando usuários existentes.

```powershell
.\mvnw.cmd '-Dtest=LocalFlowImportTests' '-Dtabula.flow.import.verify=true' test
```

Configure `spring.mongodb.uri` com URI válida e `spring.mongodb.database`. O driver Java não aceita `authMechanism=DEFAULT`; omitir esse parâmetro permite usar a negociação padrão, mantendo authSource e credenciais conforme a instalação.

A validação real foi concluída em 2026-11-08: ping MongoDB, importação IBM de Dialogs e Actions, persistência MySQL/MongoDB, replay idempotente, inicialização e atualização de rascunhos e novos snapshots passaram no teste `LocalFlowImportTests`. O parâmetro incompatível `authMechanism=DEFAULT` foi removido da URI, preservando o restante da configuração. A migration V4 está aplicada no MySQL local. Os registros temporários foram removidos dos dois bancos e as contas existentes foram preservadas.

A suíte isolada anterior passou com 66 testes; a validação real opt-in passou separadamente. Não houve publicação nem alteração de conteúdo na IBM.

Fontes: [contrato IBM exportSkills no SDK oficial](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v2/Assistant.java) e [autenticação SCRAM no driver Java MongoDB](https://www.mongodb.com/docs/drivers/java/sync/current/security/auth/scram/).
