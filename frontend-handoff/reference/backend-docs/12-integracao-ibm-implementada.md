# Integração IBM — configuração, destinos e leitura

Atualização: 2026-11-08.

## Escopo implementado

O módulo MVC `integration` cadastra conexões IBM de portfólios e destinos de projetos. Vínculos DATABASE possuem endpoint/API key próprios; os legados PROPERTIES delegam a configuração do servidor. Possui IAM isolado, clientes HTTP v1/v2, probes e capacidades. Consulte [credenciais isoladas](20-instancias-ibm-por-portfolio.md).

As rotas de leitura desta página não enviam mensagens nem criam sessões. Importação/exportação local de fluxos e orquestração de conversas foram acrescentadas posteriormente nos documentos 13 e 14. Alteração/publicação de conteúdo IBM continua fora do escopo. A existência de leitura autorizada não comprova permissão de escrita ou execução.

## Persistência e credencial

A migration `V3__create_ibm_integrations.sql` cria:

- `ibm_integrations`: ID, portfólio, nome, status e versão otimista.
- `ibm_targets`: projeto, integração, nome, tipo de ambiente, IDs IBM, datas de versão das APIs, status e versão otimista.

Há no máximo uma integração por portfólio. A migration V7 adiciona endpoint, origem, ciphertext AES-GCM e revisão para conexões DATABASE. Chave em claro e token IAM não são persistidos nessas tabelas nem retornados pela API. O frontend pode fornecer endpoint público IBM permitido e API key. Um destino pertence ao mesmo portfólio da integração e não pode ser movido para outro projeto ou integração por atualização.

Somente vínculos PROPERTIES compartilham a credencial global, com cadastro exclusivo do administrador global. OWNER/admin pode cadastrar uma conexão DATABASE própria. A chave mestra de proteção vem do ambiente seguro do servidor e o ciphertext fica no MySQL. Endpoint/portfólio são imutáveis e não há conversão automática de vínculos legados.

## Autorização

| Operação | Acesso |
|---|---|
| Vincular credencial configurada a um portfólio | Administrador global |
| Vincular instância com endpoint/API key próprios | OWNER ou administrador global |
| Rotacionar API key DATABASE | OWNER ou administrador global |
| Listar/consultar integração, destinos e capacidades | Membros do portfólio ou administrador global |
| Renomear/ativar/desativar integração | OWNER ou administrador global |
| Consultar IDs sugeridos do properties | OWNER ou administrador global |
| Criar/substituir/desativar destino | OWNER ou administrador global |
| Testar conexão da integração ou destino | OWNER ou administrador global |

EDITOR, OPERATOR e VIEWER têm leitura de metadados nesta entrega. Não membros recebem 404; membros sem a permissão exigida recebem 403. Comandos exigem CSRF. Portfólios/projetos arquivados não permitem criação, alteração ou testes remotos. A integração desativada bloqueia novos destinos e testes; destino desativado bloqueia seu teste. Consultas continuam disponíveis.

Os serviços usam os contratos de `portfolio` e `identity`, sem acessar seus repositories. As transações de autorização e carregamento terminam antes de chamadas HTTP IBM. Alterações iniciadas depois da autorização não cancelam uma consulta remota já em andamento.

## Configuração do servidor

| Propriedade | Origem alternativa/padrão |
|---|---|
| `ibm.watson.service-url` | `IBM_SERVICE_URL` |
| `ibm.watson.api-key` | `IBM_API_KEY` |
| `ibm.watson.v1.version` | `2021-11-27` |
| `ibm.watson.v2.version` | `2024-08-25` |
| `ibm.watson.connect-timeout-seconds` | 5; permitido de 1 a 60 |
| `ibm.watson.read-timeout-seconds` | 20; permitido de 1 a 120 |
| `ibm.watson.max-concurrent-probes` | 2; permitido de 1 a 20 |

As datas são escolhas explícitas de contrato baseadas no SDK oficial consultado, não resolução automática da versão mais recente. Cada destino guarda suas próprias datas, validadas como YYYY-MM-DD.

IDs sugeridos pelo endpoint de defaults são lidos de `IBM_ASSISTANT_ID`, `IBM_ACTION_SKILL_ID`, `IBM_DIALOG_SKILL_ID`, `IBM_DRAFT_ENV_ID`, `IBM_LIVE_ENV_ID` e, se preenchido, `IBM_DIALOG_WORKSPACE_ID`.

`workspaceId`, `skillId`, `assistantId` e `environmentId` permanecem separados. Não inferir o workspace a partir da skill: na validação local, a consulta v1 usando o ID da skill retornou 404.

Na configuração atual, o usuário utiliza Dialogs dentro do assistant v2. O acesso de referência é pela combinação já existente `IBM_ASSISTANT_ID` + `IBM_DIALOG_SKILL_ID`, no endpoint `GET /v2/assistants/{assistant_id}/skills/{skill_id}`. A leitura real confirmou que os IDs retornados correspondem à configuração e que o tipo da skill é `dialog`. O cliente `IbmV2ReadClient.skill` e o check DIALOG_SKILL já usam esse caminho.

`IBM_DIALOG_WORKSPACE_ID` é opcional para o acesso direto a workspaces pela API v1; sua ausência não bloqueia o acesso ao Dialog pela v2. O check DIALOG_WORKSPACE=NOT_CONFIGURED representa apenas essa modalidade opcional. A consulta de skill confirma metadados; obtenção do JSON completo e autoria de conteúdo exigem seus contratos próprios na próxima etapa.

Para cadastrar um destino v1, basta informar `dialogWorkspaceId`. Recursos v2 exigem `assistantId` e ao menos um `environmentId`, `dialogSkillId` ou `actionSkillId`. IDs aceitam letras ASCII, números, hífen e underscore, com até 128 caracteres. DRAFT/LIVE é um rótulo local; a resposta IBM pode confirmar acesso ao ambiente, mas o backend ainda não verifica se o ID informado corresponde semanticamente ao rótulo escolhido.

## Contratos HTTP

| Método | Rota | Finalidade |
|---|---|---|
| POST | `/api/v1/portfolios/{id}/integrations` | Vincular configuração IBM; corpo `{"name":"IBM principal"}`; 201 |
| GET | `/api/v1/portfolios/{id}/integrations` | Array de zero ou uma integração |
| GET | `/api/v1/integrations/{id}` | Metadados do vínculo |
| PATCH | `/api/v1/integrations/{id}` | Campos opcionais `name` e `active` |
| DELETE | `/api/v1/integrations/{id}` | Desativar vínculo; 204 |
| GET | `/api/v1/integrations/{id}/defaults?environmentType=DRAFT` | Configuração sugerida de destino, sem credencial |
| POST | `/api/v1/integrations/{id}/connection-tests` | Testar IAM e listagens v1/v2 |
| POST | `/api/v1/projects/{id}/targets` | Criar destino; 201 |
| GET | `/api/v1/projects/{id}/targets` | Destinos paginados, inclusive desativados |
| GET | `/api/v1/targets/{id}` | Consultar destino |
| PUT | `/api/v1/targets/{id}` | Substituir configuração completa |
| DELETE | `/api/v1/targets/{id}` | Desativar destino; 204 |
| GET | `/api/v1/targets/{id}/capabilities` | Capacidades implementadas/configuradas |
| POST | `/api/v1/targets/{id}/connection-tests` | Testar leituras dos IDs configurados |

Listagem de destinos usa `page` a partir de zero, `size` entre 1 e 100 (padrão 20) e ordenação por ID. Retorna o mesmo envelope de paginação de portfólios. IDs das rotas são UUIDs locais, não IDs IBM.

Exemplo de criação de destino; substituir os valores pelos IDs reais autorizados:

```json
{
  "integrationId": "fd11c37f-6130-4b4d-8d70-25a3b1054f2c",
  "configuration": {
    "name": "Assistant Draft",
    "environmentType": "DRAFT",
    "assistantId": "assistant-id",
    "environmentId": "draft-environment-id",
    "dialogWorkspaceId": null,
    "dialogSkillId": "dialog-skill-id",
    "actionSkillId": "action-skill-id",
    "v1Version": "2021-11-27",
    "v2Version": "2024-08-25"
  }
}
```

O PUT recebe apenas o objeto `configuration`, sem o envelope de criação. Campos de IDs ausentes/nulos são removidos; não é PATCH parcial. O status ativo não é modificado pelo PUT. Não há reativação pública de destino nesta etapa; cadastrar um novo destino é possível. A integração pode ser reativada por PATCH. DELETE nunca exclui recursos IBM.

## Testes de conexão e capacidades

O teste retorna HTTP 200 quando a consulta local foi autorizada e realizada, com `checkedAt`, `successful` e `checks`. Uma falha IBM aparece no resultado do check, não como falha de autenticação do usuário Tabula. Erros locais de autorização, validação, recurso desativado ou arquivado mantêm seus status 4xx.

Checks possíveis:

- `AVAILABLE`: chamada concluída com sucesso e resposta JSON objeto.
- `FAILED`: erro autenticando, conectando ou consultando o recurso; `code` sanitizado identifica a causa.
- `UNSUPPORTED`: restrição da operação reconhecida na resposta IBM.
- `NOT_CONFIGURED`: ID opcional não preenchido; nenhuma chamada para esse recurso.

`successful` só é true quando todos os checks são AVAILABLE ou NOT_CONFIGURED. Não representa compatibilidade completa de produto, treinamento ou execução. O corpo remoto não é devolvido nem persistido pelo teste.

A consulta de capabilities distingue operação implementada, presença dos IDs e estado de permissão. Ela não chama a IBM e retorna `permissionStatus=NOT_VERIFIED`, mesmo após um probe anterior, pois os resultados não são persistidos. `UPDATE_DRAFT_SKILL` indica atualização de conteúdo de skill existente no Draft, sem comprovar disponibilidade no plano remoto. `WRITE_FLOW` representa o conjunto completo de autoria/publicação, ainda não implementado; envio de mensagens aparece implementado pela orquestração de atendimentos.

## Transporte e proteção de credenciais

- IAM via `POST https://iam.cloud.ibm.com/identity/token`, com API key em formulário e grant de API key.
- Token Bearer mantido somente em memória, reutilizado até a expiração com margem de 60 segundos; 401 invalida o cache para a próxima tentativa explícita.
- Spring RestClient com cliente HTTP JDK, timeouts e redirecionamentos desabilitados.
- URL do serviço restrita a HTTPS, porta padrão/443, hosts públicos `api.<região>.assistant.watson.cloud.ibm.com` e caminho opcional `/instances/<id>`. URLs privadas, IBM CP4D e domínios personalizados não são suportados nesta entrega.
- Limite de 1 MiB por resposta e limite global de probes simultâneos por processo. O limite é global porque a credencial é compartilhada.
- Sem retries automáticos, inclusive em 429. O cliente recebe código sanitizado e pode realizar nova tentativa explícita. Não há backoff ou estado de retry persistido nesta etapa.
- Mensagens de exceção, tokens e corpo de resposta IBM não são expostos. Evitar habilitar logging de corpos HTTP contendo autenticação.

## Evidência local e limitação observada

Na instância configurada, IAM, listagem v1, ambientes Draft/Live e skills de Dialogs/Actions responderam com sucesso. A listagem geral v2 retornou HTTP 400 com a mensagem `API is not supported on lite plan`. Essa resposta específica é traduzida para `IBM_OPERATION_NOT_SUPPORTED` e check UNSUPPORTED; não bloqueia leituras das skills e ambientes que funcionam. Isso não implica indisponibilidade geral da API v2 nem invalida os testes prévios de autoria relatados pelo usuário.

O ID do workspace v1 só precisa ser informado se for necessário testar sua leitura direta pela API v1. Para o Dialog associado ao assistant v2, os IDs necessários já estão configurados e foram validados. Não houve envio de mensagem ou mutação de conteúdo remoto durante a validação.

## Testes automatizados

`IbmHttpClientTests` simula IAM e IBM com MockRestServiceServer, verificando formulário, token, caminhos, tratamento de status, rejeição de destinos inseguros, payload máximo, JSON inválido e timeout. `IntegrationModuleTests` usa H2 e transporte simulado para autorização, persistência, isolamento, validação, desativação e chamadas fora de transação.

O teste `LocalIbmReadTests` é opt-in, usa MySQL e IBM configurados, cria registros locais temporários e os remove em `finally`. Não altera o administrador permanente e só realiza autenticação IAM e GETs remotos. A restrição reconhecida da listagem v2 é registrada separadamente; leituras configuradas dos destinos precisam passar.

```powershell
.\mvnw.cmd '-Dtest=LocalIbmReadTests' '-Dtabula.ibm.read.verify=true' test
```

O comando aplica migrations locais pendentes. A suíte comum mantém esse teste desabilitado e não usa as credenciais reais.

Resultado desta entrega: 52 testes isolados aprovados e validação MySQL/IBM opt-in aprovada. A migration V3 foi aplicada no banco local. A validação registra a listagem v2 como UNSUPPORTED e o workspace v1 como NOT_CONFIGURED; IAM e os demais recursos configurados passaram. Os registros temporários foram removidos e o administrador permanente foi preservado.

## Fluxo de produto definido para o frontend

O frontend poderá criar um portfólio e vinculá-lo a uma instância IBM. A importação será solicitada pela API Tabula; o backend acessará a API IBM da instância vinculada, obterá Dialog/Action e persistirá o conteúdo original no MongoDB. O usuário não precisa fornecer um arquivo JSON para esse fluxo, embora upload permaneça como alternativa existente.

Sequência de endpoints já disponíveis:

1. `POST /api/v1/portfolios` cria o portfólio.
2. `POST /api/v1/portfolios/{id}/integrations` registra o vínculo IBM.
3. `POST /api/v1/portfolios/{id}/projects` cria o projeto local.
4. `POST /api/v1/projects/{id}/targets` associa Assistant/ambiente/skills à integração do portfólio.
5. `POST /api/v1/projects/{id}/imports` consulta a API IBM e cria o snapshot local imutável.
6. `POST /api/v1/flows/{id}/draft` inicia a cópia editável; layout e diagrama usam suas rotas próprias.

Na etapa 2, `{name, serviceUrl, apiKey}` cria DATABASE com permissão OWNER/admin. O corpo legado `{name}` mantém PROPERTIES e exige administrador global. A sequência permite uma instância própria por portfólio, sem copiar IDs globais para outro vínculo.

Endpoint/credencial próprios, proteção de chave, autorização OWNER, IAM separado e uso explícito da conexão foram implementados em probes, imports, autoria e conversas. Novos bindings DATABASE mantêm a instância de origem sem guardar segredos. Detalhes e limites estão no [documento 20](20-instancias-ibm-por-portfolio.md).

## Fontes oficiais consultadas

- [SDK oficial IBM Assistant v1](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v1/Assistant.java): contratos de leitura e data v1.
- [SDK oficial IBM Assistant v2](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v2/Assistant.java): listagem de assistants, leitura de ambiente e skill; data v2.
- [IBM IAM Identity Token API](https://cloud.ibm.com/docs/apis/iam-identity-token-api): troca da API key por token.

A etapa seguinte de importação e versionamento interno já foi implementada e validada com MongoDB/MySQL; consulte [Fluxos, importação e versões](13-fluxos-importacao-e-versoes.md). A atualização de conteúdo de skills Draft existentes foi implementada separadamente: [autoria Draft](17-autoria-ibm-draft.md). A capability `UPDATE_DRAFT_SKILL` indica esse suporte específico, sem afirmar permissão remota; `WRITE_FLOW` continua não implementada para o conjunto completo de operações. Publicação permanece pendente.
