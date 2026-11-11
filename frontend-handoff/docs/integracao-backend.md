# Integração do frontend com a API Tabula

> Para o editor, use [o contrato atual de versões editáveis](versoes-editaveis-e-vinculo-ibm.md). Ele substitui os contratos de rascunho, snapshot e layout separado deste guia para novos fluxos.

Fotografia dos controllers/DTOs em 08/11/2026. Base: `/api/v1`. IDs são UUIDs em strings. A collection e [o inventário completo](../reference/rotas-backend.md) complementam este guia. Quando acessível, o código backend atual prevalece.

## Ambiente e conexão

Use URL relativa `/api/v1` e proxy Vite para o backend. Configure HTTP local/cookies conforme [o plano inicial](plano-inicial-frontend.md). O backend possui seu próprio `.env`, MySQL e MongoDB; nenhum desses segredos entra no frontend.

Todas as chamadas usam `credentials: 'include'`. Cookie de sessão: `JSESSIONID`, HttpOnly e SameSite=Lax, com Secure habilitado por padrão no backend. O navegador cuida dele; não implementar JWT ou tentar ler o cookie por JavaScript.

O frontend se conecta apenas à API Tabula. O endpoint/API key IBM são dados de cadastro do vínculo, não autenticação do frontend. O backend administra IAM e não devolve segredos ou tokens IBM.

## Login, sessão e CSRF

1. `GET /auth/csrf` sem autenticação: retorna `{ "headerName": "X-CSRF-TOKEN", "token": "..." }` e estabelece a sessão necessária. Use o nome de header retornado.
2. `POST /auth/login`, corpo `{email,password}` e o header CSRF. Retorna a conta. Mantenha cookies também nesse POST.
3. **Busque `GET /auth/csrf` novamente após login**, porque sessão/token foram renovados. Substitua o token em memória.
4. `GET /auth/me` restaura a conta ao carregar a aplicação. 401 é sessão ausente/expirada; durante a checagem, a área protegida deve aguardar em loading.
5. `POST /auth/logout`, com CSRF e sem corpo: 204. Limpe conta, CSRF, caches de dados por usuário/portfólio e estado do editor.

GET/HEAD/OPTIONS não precisam do header CSRF; comandos POST/PUT/PATCH/DELETE precisam, inclusive login e logout. 403 pode ser falta de permissão ou CSRF inválido: não deslogue automaticamente em todo 403 nem repita a mutation cegamente.

Conta retornada: `{id,name,email,admin,active}`. `PATCH /account` aceita `{name?,email?}`; mudar email encerra a sessão. `PUT /account/password` recebe `{currentPassword,newPassword}`, retorna 204 e invalida sessões. Cadastro/troca de senha exige mínimo de 8 caracteres e máximo de 72 bytes UTF-8; o login aceita senha não vazia e valida no servidor, sem tratar a regra de criação como uma nova política de login.

Não há registro público, recuperação de senha, listagem geral/busca por email de usuários. Admin pode criar em `POST /admin/users` com `{name,email,password,admin}` (envie o booleano explicitamente), consultar/alterar por ID em `/admin/users/{id}`.

## Cliente HTTP e erros

O cliente deve disponibilizar corpo, status, `ETag`, `Location` e `Retry-After`; nem toda resposta é o mesmo envelope JSON. Trate 204 sem tentar fazer JSON.parse de corpo vazio. Exemplo de contrato de retorno local a implementar:

```ts
type ApiResult<T> = {
  data: T;
  status: number;
  etag: string | null;
  location: string | null;
  retryAfter: string | null;
};
type ApiProblem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
};
```

ProblemDetail geralmente usa `application/problem+json`. Segurança também usa esse formato. Preserve `code`, não dependa de comparar mensagens em português. **202 pode trazer um ProblemDetail (`IBM_EXPORT_PENDING`) mesmo que `response.ok` seja true.** Inspecione content-type e estrutura antes de considerar um DTO pronto.

| Situação | Comportamento de interface |
|---|---|
| 400/422 | Exibir validação/rejeição; não perder formulário/rascunho |
| 401 | Tratar sessão ausente/expirada; limpar escopos protegidos |
| 403 | Exibir acesso/CSRF; não presumir sessão inválida |
| 404 | Recurso inexistente ou inacessível; não revelar outro portfólio |
| 409 | Conflito de estado/chave/referência; consultar estado |
| 412 | Revisão antiga; recarregar/comparar, sem sobrescrever automaticamente |
| 428 | Falta de precondição ETag |
| 429/503 + IBM_RATE_LIMITED | Aguardar e reduzir consultas; não reenviar mutação remota |
| 202 | Pendente/incerto; inspecionar código/estado e manter a intenção/chave |

Consultas podem ter retries limitados conforme o erro; 401/403/404 normalmente não. Não habilitar retry indiscriminado em mutations, sobretudo mensagens e autoria IBM. Mostre código sanitizado do backend; não registre corpos com senhas/API keys ou respostas privadas em console/telemetria.

## Listagens, roles e arquivamento

Listagens paginadas retornam:

```ts
interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
```

`page` começa em 0; `size` entre 1 e 100. Onde indicado, `includeArchived=true` inclui arquivados. Lista de integrações é um array simples, não Page. Conta tem flag `admin`; membros têm `{userId,role}` com OWNER/EDITOR/OPERATOR/VIEWER. PortfolioView/ProjectView não contêm o papel da conta; consulte membros paginados quando necessário. Não assuma OWNER para todos os portfólios listados.

DELETE de portfólio/projeto/fluxo arquiva; DELETE de destino/integração desativa. Não apaga conteúdo IBM nem realiza exclusão física em cascata. Não há restauração pública de projeto/fluxo nem reativação de destino; integração admite `PATCH {active:true}`. Confirme ações de interface usando esses significados reais.

## Portfólios e projetos

| Método e rota | Corpo/retorno |
|---|---|
| POST `/portfolios` | `{name,description?}` → 201 PortfolioView |
| GET `/portfolios?page=0&size=20&includeArchived=false` | Page de portfólios acessíveis |
| GET/PATCH/DELETE `/portfolios/{id}` | PATCH `{name?,description?}`; DELETE 204 |
| GET `/portfolios/{id}/members?page=0&size=20` | Page de `{userId,role}` |
| POST `/portfolios/{id}/members` | `{userId,role}` → 201 |
| PATCH/DELETE `/portfolios/{id}/members/{userId}` | PATCH `{role}`; DELETE 204 |
| POST `/portfolios/{id}/projects` | `{name,description?}` → 201 ProjectView |
| GET `/portfolios/{id}/projects?page=0&size=20&includeArchived=false` | Page de projetos |
| GET/PATCH/DELETE `/projects/{id}` | PATCH `{name?,description?}`; DELETE 204 |

PortfolioView: `{id,name,description,archived}`. ProjectView: `{id,portfolioId,name,description,archived}`. Nome até 120 caracteres, descrição até 1.000. Uma conta ativa pode criar portfólio e torna-se OWNER. O backend impede remover o último OWNER; mostre o conflito retornado.

## Instância IBM por portfólio

Prefira a integração DATABASE:

```http
POST /portfolios/{portfolioId}/integrations
```

```json
{"name":"Minha instância","serviceUrl":"https://api.us-south.assistant.watson.cloud.ibm.com/instances/INSTANCE_ID","apiKey":"CHAVE_INFORMADA_NO_FORMULARIO"}
```

Resposta 201: `{id,portfolioId,name,active,provider,credentialSource,serviceUrl,credentialRevision}`. OWNER/admin pode cadastrar. Não existe campo de chave em consultas; a API key é criptografada no backend. Limpe o formulário e não persista a mutation/credencial no storage do navegador.

- GET `/portfolios/{id}/integrations`: array, máximo de uma integração por portfólio.
- GET/PATCH `/integrations/{id}`: PATCH `{name?,active?}`.
- PUT `/integrations/{id}/credentials`: `{apiKey}` rotaciona DATABASE; URL não muda.
- POST `/integrations/{id}/connection-tests`: relatório `{checkedAt,successful,checks}`.
- GET `/integrations/{id}/defaults?environmentType=DRAFT`: TargetConfig sugerido. DATABASE retorna IDs nulos, evitando copiar IDs globais.

O cadastro não testa automaticamente a conexão. Um probe HTTP 200 pode ter `successful=false`, com checks AVAILABLE/FAILED/UNSUPPORTED/NOT_CONFIGURED e códigos. A listagem geral IBM pode estar indisponível no plano enquanto skills específicas funcionam. Não bloquear cadastro/importação só por discovery indisponível.

URL precisa ser pública HTTPS no domínio IBM permitido pelo backend. Instância/portfólio são imutáveis após cadastro; não há migração automática de vínculo já existente. O corpo legado `{name}` cria PROPERTIES e exige admin global. Não apresentar essa alternativa como credencial isolada.

## Destinos

```http
POST /projects/{projectId}/targets
```

```json
{
  "integrationId":"UUID_DA_INTEGRACAO",
  "configuration":{
    "name":"Draft",
    "environmentType":"DRAFT",
    "assistantId":"ASSISTANT_ID",
    "environmentId":"DRAFT_ENVIRONMENT_ID",
    "dialogWorkspaceId":null,
    "dialogSkillId":"DIALOG_SKILL_ID",
    "actionSkillId":null,
    "v1Version":"2021-11-27",
    "v2Version":"2024-08-25"
  }
}
```

As datas são padrões existentes do backend, não dependências do frontend. Informe IDs da instância cadastrada; enum de ambiente DRAFT/LIVE. V2 exige assistantId e ambiente/skill conforme operação. Dialog clássico dentro do Assistant usa V2 sem workspaceId. O vínculo integração/projeto precisa pertencer ao mesmo portfólio.

GET `/projects/{id}/targets` é paginado. GET `/targets/{id}` retorna `{id,projectId,integrationId,active,configuration}`. PUT `/targets/{id}` recebe **apenas TargetConfig**, sem envelope, e substitui IDs (null remove). Não altera integrationId/estado. GET `/targets/{id}/capabilities` não confirma permissão remota (`NOT_VERIFIED`). POST `/targets/{id}/connection-tests` testa os recursos informados. Descoberta completa de IDs para seleção automática ainda não existe.

## Importação, versões e rascunhos

Import normal: POST `/projects/{id}/imports`, Idempotency-Key, corpo `{targetId,type:"DIALOG"|"ACTION",name?,flowId?}`. O backend acessa IBM. Sem flowId cria fluxo; com flowId adiciona versão ao fluxo do mesmo tipo/projeto. A exportação V2 é do Draft; destino LIVE é recusado nesse caminho.

Upload alternativo: POST `/projects/{id}/flows`, Idempotency-Key, corpo `{name,type,json}`. **`json` é uma string contendo o JSON completo da skill**, não um objeto aninhado. Exemplos mínimos:

```json
{"type":"dialog","workspace":{"dialog_nodes":[]}}
```

```json
{"type":"action","workspace":{"actions":[]}}
```

Import/upload retorna VersionView, normalmente 201 READY ou 202 PENDING. Campos: `{id,flowId,projectId,number,state,checksum,sourceType,sourceTargetId,authorId,createdAt,errorCode}`. Se responder 202 `IBM_EXPORT_PENDING`, pode não haver ID de versão ainda: aguarde Retry-After e repita a mesma requisição/chave/corpo.

| Rota | Uso |
|---|---|
| GET `/projects/{id}/flows?page=0&size=20&includeArchived=false` | Page de `{id,projectId,name,type,archived}` |
| GET/PATCH/DELETE `/flows/{id}` | Consulta, PATCH `{name}`, arquivamento |
| GET `/flows/{id}/versions?page=0&size=20` | Page de versões |
| GET `/versions/{id}` | Metadados/estado |
| GET `/versions/{id}/export` | JSON completo da skill READY, resposta attachment |
| POST `/versions/{id}/reconcile` | Finaliza reserva local se documento íntegro estiver persistido |
| POST `/flows/{id}/draft` | `{versionId}` inicializa uma vez; 200 JSON completo e ETag `"1"` |
| GET `/flows/{id}/draft` | JSON completo e ETag atual; 404 se inexistente |
| PUT `/flows/{id}/draft` | **JSON completo bruto** com If-Match; nova revisão |
| POST `/flows/{id}/versions` | Sem corpo, If-Match do draft + Idempotency-Key; snapshot |

No PUT bruto, não fazer JSON.stringify da string JSON a ponto de transformá-la em uma string JSON entre aspas. Para download/editor de código, use `response.text()` preservando números extensos: JSON.parse/stringify do navegador pode perder precisão. Não serialize `data.original` do diagrama como fonte completa. Checksums são calculados pelo backend.

O limite de skill é 4 MiB/profundidade 64. Um snapshot preserva a skill inteira selecionada, mas não o Assistant inteiro. Salvamento do rascunho não aplica na IBM. Não há worker de reconciliação automática; polling apenas de metadados não executa a reconciliação.

## Diagrama e layout

GET `/flows/{id}/diagram` usa rascunho; GET `/versions/{id}/diagram` usa versão READY. Retorna revisões/checksum de origem, nodes, edges, viewport, layoutRevision e diagnostics. Não chama IBM. O layout é o **atual do fluxo**, mesmo na consulta de versão antiga.

- Node `id`: hash visual estável, usado no layout.
- `data.sourceId`: ID IBM; `data.sourceRef`: JSON Pointer da revisão exata; `data.original`: fragmento preservado para inspeção.
- Tipos de componentes: ibmDialog, ibmAction e group. Pais visuais aparecem antes dos filhos.
- Arestas `data.relation`: PARENT (hierarquia), EVALUATION_ORDER (ordem dos irmãos), JUMP (salto explícito). Não equivalem todas a execução incondicional.
- `readOnly`/`data.editable`: capacidade limitada de formato, não autorização. `semanticComplete=false` permanece. Actions mostram um nó por ação, sem passos internos.

GET `/flows/{id}/layout`: JSON visual e ETag `"layout-N"`; 404 LAYOUT_NOT_FOUND se não criado. PUT na mesma rota cria com **If-None-Match: \*** (201) ou substitui com If-Match `"layout-N"` (200). Não enviar as duas precondições juntas.

```json
{
  "schemaVersion":1,
  "nodes":[{"id":"ID_VISUAL_DO_DIAGRAMA","position":{"x":100,"y":200},"collapsed":false}],
  "viewport":{"x":0,"y":0,"zoom":1}
}
```

Node de layout aceita id, position, parentId?, width?, height?, collapsed?. São metadados visuais; use dimensões como estilos no React Flow. parentId não muda hierarquia IBM. Não envie edges, data ou toObject() inteiro: campos desconhecidos são recusados. Grupos precisam existir no layout, sem ciclos; até 5.000 nós/1 MiB. Revisão de layout não é revisão do rascunho.

Há PATCH `/flows/{id}/draft/dialog-nodes` para edição limitada local, com If-Match do rascunho:

```json
{"nodeId":"ID_IBM_DATA_SOURCE_ID","title":"Novo titulo","conditions":"#saudacao","textEdits":[{"responseIndex":0,"valueIndex":0,"text":"Olá"}]}
```

Omitir campos preserva-os; texto só edita valores literais existentes em output.generic de resposta text. Não cria nós/respostas/conexões. Versões, nós complexos e Actions não são editados por esse comando. O tradutor e salvamento unificado de conteúdo/layout foram adiados; não inventar um PUT de diagrama que substitua o documento IBM.

## Aplicação no Draft IBM

1. Criar versão local READY.
2. POST `/projects/{id}/ibm-writes`, Idempotency-Key, `{targetId,versionId}` → operação preparada com backup; ainda não altera a IBM.
3. GET `/ibm-writes/{id}` ou lista paginada `/projects/{id}/ibm-writes`; GET `/ibm-writes/{id}/backup` fornece workspace anterior.
4. POST `/ibm-writes/{id}/apply`, sem corpo: única mutação remota da operação.
5. POST `/ibm-writes/{id}/reconcile`, sem corpo: consulta a IBM e confirma o conteúdo; nunca reenvia a escrita.

Estados relevantes: PREPARING, READY, CHECKING, SENDING, PROCESSING, UNKNOWN, SUCCEEDED, CONFLICT, FAILED. Processamento aceito não é sucesso; reconciliar até confirmação, com espera controlada/rate limit. GET de metadados não executa reconcile. Em UNKNOWN, não criar outra chave para contornar bloqueio.

Aplicação atualiza o workspace de uma skill **existente no Draft**, mantendo identidade; não cria/exclui a skill inteira e não publica em Live. O backend detecta divergência desde a preparação e mantém backup, mas não possui compare-and-swap remoto IBM nem rollback automático.

## Conversas e histórico

- POST `/projects/{id}/conversations`: Idempotency-Key, `{targetId,mode:"V2"}`. Retorna View com id/state, não session_id IBM. 201 ACTIVE, 202 OPENING/OPEN_UNKNOWN, 502 OPEN_FAILED; inspecione state.
- POST `/conversations/{id}/messages`: Idempotency-Key, `{text}` (não vazio, até 8.000 caracteres); 200 COMPLETED, 422 FAILED, 202 pendente/incerto.
- GET `/projects/{id}/conversations`, GET `/conversations/{id}`.
- GET `/conversations/{id}/messages?page=0&size=20`, GET `/conversations/{id}/messages/{turnId}`.
- POST `/conversations/{id}/messages/{turnId}/reconcile`, POST `/conversations/{id}/reconcile`.
- POST `/conversations/{id}/close`: 200 CLOSED ou 202 incerto.

TurnView: `{id,sequence,state,text,output,createdAt,errorCode}`. Outputs são tipos genéricos: implementar renderizadores suportados, não assumir somente texto nem executar conteúdo arbitrário. Estados do turno incluem RESERVED/SENDING/COMPLETED/FAILED/UNKNOWN; nova mensagem não contorna uma anterior incerta. Não recriar sessão automaticamente após expiração. Reconciliação não reenvia mensagens.

Não há SSE/WebSocket/chat humano, fila, responsável, transferência ou SLA implementados. Histórico possui consultas paginadas existentes, sem pressupor filtros avançados.

## Idempotência e chamadas assíncronas

Gere, por exemplo, `crypto.randomUUID()` uma vez por intenção (import/upload/snapshot/abertura/mensagem/preparação de escrita). Reutilize somente com corpo idêntico em tentativas da mesma intenção. Outra intenção exige outra chave. Backend aceita 8–100 caracteres ASCII `[A-Za-z0-9._:-]`; reutilização incompatível retorna 409.

Salve o identificador da operação antes de polling; se o usuário navegar, ofereça retomada pela listagem. Evite loops curtos de exportação IBM: houve 429 real e confirmação após espera. Respeite Retry-After quando disponível e use espera limitada. Ações de aplicar na IBM e enviar mensagens são explícitas; não execute a collection inteira ou publique como efeito de montar uma tela.
