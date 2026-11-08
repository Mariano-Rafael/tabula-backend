# Atendimentos IBM v1/v2 e histórico

Atualização: 2026-11-08.

## Escopo

O backend abre atendimentos, encaminha mensagens à IBM, mantém histórico e encerra sessões. Todo processamento de linguagem e execução de Dialogs/Actions é delegado à IBM. Não existe interpretador local, engine própria ou execução dos snapshots JSON importados no Tabula.

- `conversation`: ciclo de vida, autorização, idempotência, ordenação e confirmação dos turnos.
- `integration`: gateway HTTP IBM v1/v2, IAM e tradução de erros.
- `history`: consultas de atendimentos e mensagens; não executa conversas.

O atendimento copia os identificadores do destino, o modo e a data de versão da API na abertura. Alterar o cadastro do destino não redireciona um atendimento existente. Desativar destino/integração impede novos envios. Essa cópia não fixa uma release IBM e não garante que o conteúdo remoto permaneça inalterado durante o atendimento.

## Modos suportados

| Modo | Configuração necessária | Estado conversacional |
|---|---|---|
| V1 | `dialogWorkspaceId` e data de versão v1 | O backend reenvia o contexto da última resposta confirmada |
| V2 | `assistantId`, `environmentId` e data de versão v2 | Sessão stateful mantida pela IBM |

O cenário atual de Dialogs e Actions dentro do assistant usa V2. Um Dialog clássico (frequentemente chamado de “v1 dentro da v2”) é executado pelo Assistant via API V2, sem `workspaceId`. Neste contrato, `mode` identifica a API de transporte, não o tipo de conteúdo: `V2` atende tanto Dialog quanto Actions conforme a configuração e o roteamento do Assistant. Não é necessário selecionar `mode=V1` para conversar com um Dialog associado ao Assistant.

O modo `V1` significa exclusivamente chamada direta ao endpoint legado `/v1/workspaces/{workspace_id}/message`. Somente essa alternativa exige `dialogWorkspaceId`; sem ele retorna 422 com `IBM_WORKSPACE_REQUIRED`. Sua ausência não é uma pendência para o cenário atual de Dialog via V2. O ID de skill não é convertido automaticamente em workspace v1.

Referências oficiais: [visão geral das APIs](https://cloud.ibm.com/docs/watson-assistant?topic=watson-assistant-api-overview), [migração para V2](https://cloud.ibm.com/docs/watson-assistant?topic=watson-assistant-api-migration) e [referência V2](https://cloud.ibm.com/docs/apis/assistant-v2?code=java).

Os caminhos usados pelo gateway são:

```text
POST   /v1/workspaces/{workspace_id}/message
POST   /v2/assistants/{assistant_id}/environments/{environment_id}/sessions
POST   /v2/assistants/{assistant_id}/environments/{environment_id}/sessions/{session_id}/message
DELETE /v2/assistants/{assistant_id}/environments/{environment_id}/sessions/{session_id}
```

Todos incluem a data de versão e autenticação IAM Bearer. V1 envia input.text e o contexto persistido. V2 envia input.message_type=text e input.text, sem permitir que o cliente sobrescreva o contexto. O user_id enviado ao provedor é o UUID local do atendimento, não o email do operador.

Abrir V1 não envia uma mensagem inicial. Abrir V2 cria somente a sessão; não envia saudação automaticamente. A entrada atual é texto de 1 a 8000 caracteres não vazios. As saídas preservam a estrutura `output.generic`; respostas v1 antigas em `output.text` são normalizadas para itens textuais.

## Permissões

OWNER, EDITOR, OPERATOR e administrador global podem abrir, enviar, reconciliar e encerrar atendimentos. VIEWER possui somente leitura. Os atendimentos são recursos do projeto: não ficam restritos ao usuário que os criou; `createdBy` registra a autoria da abertura.

Todas as consultas validam acesso ao portfólio. Não membros recebem 404. Membros sem permissão operacional recebem 403. Comandos exigem CSRF.

Projeto/portfólio arquivado ou destino/integração desativada bloqueiam abertura e novos envios. Encerramento e reconciliação operacional continuam permitidos a operadores autorizados para possibilitar limpeza de sessões, mesmo após arquivamento/desativação. Alterações de autorização não cancelam uma chamada já em andamento.

## Rotas implementadas

| Método | Rota | Finalidade |
|---|---|---|
| POST | `/api/v1/projects/{id}/conversations` | Abrir atendimento |
| GET | `/api/v1/projects/{id}/conversations` | Listar atendimentos paginados |
| GET | `/api/v1/conversations/{id}` | Consultar estado |
| POST | `/api/v1/conversations/{id}/messages` | Enviar mensagem ou consultar replay idempotente |
| GET | `/api/v1/conversations/{id}/messages` | Histórico paginado por sequência |
| GET | `/api/v1/conversations/{id}/messages/{turnId}` | Consultar tentativa individual |
| POST | `/api/v1/conversations/{id}/messages/{turnId}/reconcile` | Confirmar resposta persistida ou classificar tentativa interrompida |
| POST | `/api/v1/conversations/{id}/close` | Encerrar atendimento e sessão quando conhecida |
| POST | `/api/v1/conversations/{id}/reconcile` | Classificar abertura/encerramento interrompido após o prazo |

Listagens usam `page` a partir de zero e `size` de 1 a 100, padrão 20. Atendimentos são ordenados por ID; mensagens, por sequência. Não há streaming, SSE ou WebSocket nesta entrega.

Corpo de abertura:

```json
{"targetId":"2d42dfd5-00c5-4754-b26b-27d86a76b885","mode":"V2"}
```

Corpo de mensagem:

```json
{"text":"Olá"}
```

`targetId` é um UUID local. Identificadores e credenciais IBM são resolvidos pelo servidor. Abertura e envio exigem `Idempotency-Key` de 8 a 100 caracteres ASCII: letras, números, ponto, hífen, underscore ou dois-pontos. O escopo da abertura é o projeto; o da mensagem é o atendimento. Reutilizar uma chave com outro corpo retorna 409.

## Estados e respostas HTTP

Estados do atendimento:

| Estado | Significado |
|---|---|
| OPENING | Criação de sessão v2 em andamento |
| ACTIVE | Pronto para receber mensagens |
| OPEN_FAILED | Abertura recusada de forma conhecida |
| OPEN_UNKNOWN | Não foi possível confirmar o resultado da criação da sessão |
| BLOCKED | Existe mensagem com resultado incerto; novos envios bloqueados |
| SESSION_EXPIRED | A sessão/recurso v2 não foi encontrado ao enviar |
| CLOSING | Encerramento remoto em andamento |
| CLOSE_UNKNOWN | Encerramento não confirmado; repetir close é permitido |
| CLOSED | Atendimento encerrado localmente |

Um 404 em mensagem v2 é registrado como `IBM_SESSION_UNAVAILABLE`: pode representar sessão expirada ou outro recurso remoto ausente. Não há recriação automática, pois isso poderia perder contexto. Para continuar, abrir outro atendimento explicitamente.

Estados do turno: RESERVED, SENDING, COMPLETED, FAILED e UNKNOWN. A sequência é monotônica e inclui tentativas falhas; não reutiliza números.

- Abertura ACTIVE retorna 201 e Location; OPENING/OPEN_UNKNOWN retornam 202; OPEN_FAILED retorna 502 com os metadados do atendimento. Replay de atendimento já em outro estado retorna 200.
- Mensagem COMPLETED retorna 200; FAILED retorna 422; RESERVED/SENDING/UNKNOWN retornam 202. O corpo contém id, sequence, state, text, output, createdAt e errorCode.
- Close retorna 200 quando CLOSED e 202 se ainda não confirmado. Repetir close de CLOSED não chama novamente a IBM.
- Falhas locais de validação, autorização, armazenamento ou concorrência retornam ProblemDetail sanitizado. Nunca retornar o corpo de erro original da IBM como mensagem de erro do servidor.

O 202 não comprova entrega nem sucesso no processamento. O cliente deve consultar estado/histórico, mantendo a mesma chave. Usar nova chave para contornar um turno UNKNOWN não é permitido pelo backend.

## Ordenação, contexto e durabilidade

A migration `V5__create_conversations.sql` cria `conversations` e `conversation_turns` no MySQL. Nele ficam IDs, vínculo copiado, estado, sequência, hashes, prazos e o identificador privado da sessão. Textos, respostas e contexto ficam no MongoDB, na coleção `conversation_documents`.

Cada documento MongoDB possui escopo de portfólio/projeto/atendimento, sequência, requestHash, texto e, quando recebida, resposta JSON e responseHash. O contexto v1 faz parte dessa resposta durável; não há atualização independente de um segundo documento de contexto. O MySQL aponta para o último turno confirmado.

Fluxo de envio:

1. Autorizar, bloquear o registro do atendimento e reservar a tentativa no MySQL. Apenas uma tentativa ativa é permitida.
2. Persistir a entrada no MongoDB. Se falhar, não chamar a IBM.
3. Recuperar e verificar o último contexto confirmado, quando V1.
4. Confirmar SENDING no MySQL antes de chamar o provedor.
5. Enviar uma única vez, sem retries automáticos de mensagem.
6. Persistir a resposta MongoDB sem sobrescrever uma resposta existente.
7. Confirmar COMPLETED e avançar o ponteiro de contexto no MySQL.

As transações MySQL não permanecem abertas durante chamadas IBM ou MongoDB. Bloqueios e chaves únicas serializam reservas inclusive entre instâncias. Hashes e escopo são conferidos antes de devolver mensagens confirmadas. Os corpos de resposta seguem o limite de 1 MiB do transporte.

Repetir a mesma chave devolve a tentativa existente, inclusive se ainda SENDING ou UNKNOWN. Não cria outro turno nem chama o provedor outra vez. Uma segunda mensagem com chave diferente recebe 409 enquanto houver tentativa ativa ou atendimento bloqueado.

## Falhas e reconciliação

Não há transação distribuída nem garantia de execução exatamente uma vez na IBM. Timeouts, falhas de transporte e respostas inválidas após o envio são conservadoramente UNKNOWN. Recusas conhecidas, como autenticação/permissão, payload rejeitado e rate limit, são FAILED, sem avançar o contexto.

Se a resposta já existir no MongoDB e seus hashes/estrutura forem válidos, a reconciliação confirma o turno sem reenviar mensagem. Se não houver resposta:

- RESERVED com prazo vencido vira FAILED (`TURN_NOT_DISPATCHED`), pois o envio requer uma confirmação posterior de SENDING.
- SENDING com prazo vencido vira UNKNOWN e bloqueia o atendimento.
- UNKNOWN permanece incerto; não é convertido em falha só porque não há resposta local.

O prazo da tentativa é de dois minutos. Antes do vencimento, uma tentativa ainda em andamento pode retornar 409 na reconciliação. Não há worker automático, reenvio ou botão de "assumir sucesso" nesta entrega.

Uma conversa BLOCKED pode ser encerrada, mas isso não resolve retroativamente o resultado da mensagem. Se uma resposta durável for confirmada depois do encerramento, o histórico pode ser concluído sem reabrir o atendimento.

## Abertura e encerramento v2

A criação de sessão também não é repetida automaticamente. Se a IBM criar uma sessão e a confirmação SQL falhar, o estado pode ser OPEN_UNKNOWN. Sem sessionId confirmado não é possível localizar essa sessão remota pelo backend; ela pode permanecer até expirar no provedor. Encerrar localmente esse caso mantém `REMOTE_SESSION_UNCONFIRMED`, sem afirmar que houve limpeza remota.

Uma abertura/close interrompida permanece em progresso até reconciliação explícita após o prazo. OPENING vira OPEN_UNKNOWN; CLOSING vira CLOSE_UNKNOWN. O endpoint de reconciliação de ciclo de vida não chama a IBM.

O DELETE de sessão pode ser repetido explicitamente após CLOSE_UNKNOWN; 404 é tratado como sessão já ausente. Esse comportamento não implica retry de mensagens. V1 não possui sessão remota nessa integração e fecha apenas o atendimento local.

## Privacidade e limites

O histórico público contém entrada e saídas normalizadas. Não expõe sessionId, binding interno, contexto completo, token IAM ou credenciais. A resposta bruta/contexto fica no armazenamento restrito do servidor para continuidade e reconciliação.

Não foram implementados fila humana, transferência de responsável, SLA, anexos de entrada, busca textual, retenção automática, anonimização, auditoria administrativa persistida ou visualização do percurso em React Flow. Essas funcionalidades precisam de seus contratos próprios.

## Validação

`IbmConversationGatewayTests` usa MockRestServiceServer para verificar verbos, caminhos, corpo/contexto v1, sessão v2, normalização, DELETE 204/404 e ausência de retry. `ConversationModuleTests` usa H2 e armazenamento/provedor simulados para idempotência, concorrência, falhas, acesso, sessão expirada, histórico e reconciliação.

`LocalConversationTests` é opt-in e usa MySQL/MongoDB reais com o gateway IBM **totalmente simulado**. Aplica a migration, usa fixtures isoladas e verifica ambos os modos, contexto, replay idempotente, histórico, resposta imutável e encerramento. Remove somente seus registros temporários e preserva o administrador permanente.

```powershell
.\mvnw.cmd '-Dtest=LocalConversationTests' '-Dtabula.conversation.local.verify=true' test
```

Na validação inicial com `LocalConversationTests`, não houve envio de mensagens aos assistants configurados. Em 08/11/2026, `RealIbmConversationTests` passou com a mensagem autorizada `Primeira Action`, gateway IBM real e bancos reais: abertura V2 no Draft, mensagem COMPLETED, replay idempotente, histórico com um turno e encerramento CLOSED. Os registros temporários foram removidos. Isso valida o caminho pelos serviços internos; não comprova todas as rotas HTTP ou qual ramificação/skill do Assistant foi acionada. V1 direto não foi executado por ausência de workspaceId. Consulte [o registro da execução](16-collection-bruno-e-testes-ibm.md).

O teste local com MySQL e MongoDB foi concluído: ambos os modos passaram pelo ciclo de abertura, mensagem, contexto, replay, histórico e encerramento. A migration V5 está aplicada. Os registros temporários foram removidos e o administrador permanente foi preservado.

A suíte final passou com 87 testes isolados, incluindo 21 casos novos de atendimentos/gateway. Os testes opt-in permanecem desabilitados por padrão. A validação separada com bancos reais usou gateway simulado e também passou.

A chave real é resolvida por `ibm.watson.api-key=${IBM_API_KEY:}`. A variável `IBM_API_KEY` deve estar no ambiente de execução para chamadas reais. Não definir `IBM_API_KEY=${IBM_API_KEY:}` dentro do properties, pois isso cria referência circular quando a variável não está presente. Os testes de bancos reais com gateway simulado não precisam da chave.

Fontes de contrato: [SDK oficial IBM Assistant v1](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v1/Assistant.java) e [SDK oficial IBM Assistant v2](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v2/Assistant.java).
