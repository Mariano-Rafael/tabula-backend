# Instâncias IBM por portfólio

Atualização: 08/11/2026.

Cada portfólio pode cadastrar um endpoint/API key próprios. A credencial é criptografada antes de persistir no MySQL. Probes, imports, autoria e sessões usam clientes explícitos da integração, sem alterar propriedades globais ou usar thread-local.

## Cadastro

OWNER/admin, sessão autenticada e CSRF:

```http
POST /api/v1/portfolios/{portfolioId}/integrations
Content-Type: application/json
X-CSRF-TOKEN: <token>
```

```json
{
  "name": "IBM do portfólio",
  "serviceUrl": "https://api.us-south.assistant.watson.cloud.ibm.com/instances/INSTANCE_ID",
  "apiKey": "API_KEY_FORNECIDA_PELO_USUARIO"
}
```

Endpoint e API key devem ser fornecidos juntos. URL restrita a endpoints públicos HTTPS IBM Cloud permitidos pela política existente; sem URLs privadas, query, fragmento, credenciais na URL ou redirecionamentos. CP4D/domínios personalizados não são suportados. API key: 1–2048 caracteres ASCII imprimíveis, sem espaços/controle.

Resposta 201: `id`, `portfolioId`, `name`, `active`, `provider=IBM`, `credentialSource=DATABASE`, `serviceUrl`, `credentialRevision=1`. Nenhuma API key, ciphertext ou token é retornado. DTOs não serializam apiKey e seu toString é redigido. Cadastro não faz chamada remota; use `/integrations/{id}/connection-tests` para verificar a chave.

O corpo legado `{name}` continua disponível somente para administrador global, criando integração PROPERTIES. Não é fallback para credenciais isoladas inválidas. Há no máximo uma integração por portfólio. Endpoint/portfólio do vínculo são imutáveis; não há conversão automática do vínculo legado ou substituição de instância nesta entrega. Uma conexão nova deve ser cadastrada em portfólio sem integração.

Consultas de metadados/endpoint são permitidas a membros; não membros recebem 404. EDITOR/OPERATOR/VIEWER não gerenciam credenciais. Escritas exigem CSRF e gestão do portfólio ativo. Desativação impede novas operações; encerramento/reconciliação seguem seus contratos anteriores.

## Rotação

`PUT /api/v1/integrations/{id}/credentials`, com `{ "apiKey": "NOVA_API_KEY" }`, OWNER/admin e CSRF. Retorna 200 com metadados e revisão incrementada. Não altera endpoint. PROPERTIES retorna 409 `IBM_LEGACY_CREDENTIAL`: sua chave continua administrada no servidor.

Na próxima chamada, a revisão atual cria um novo cliente/token provider. Requisições já iniciadas podem terminar com a chave anterior. Não há reenvio automático de mensagens ou escritas. A rotação não verifica automaticamente se a nova chave tem as mesmas permissões IBM.

## Uso pelo frontend

1. Criar portfólio e integração DATABASE.
2. Criar projeto e destino com a integração do mesmo portfólio e IDs do Assistant/ambiente/skills dessa instância.
3. Solicitar `/projects/{id}/imports`: o backend consulta a API IBM correta e salva o JSON original no MongoDB.
4. Inicializar rascunho e consultar layout/diagrama pelas rotas existentes.

Para DATABASE, `/integrations/{id}/defaults` retorna datas/nome e IDs de recursos nulos. Não copia IDs globais do properties para outra instância. IDs são informados no destino; descoberta completa para seleção no frontend é etapa posterior. PROPERTIES mantém os defaults antigos.

## Proteção e persistência

V7 acrescenta a `ibm_integrations`: `credential_source`, `service_url`, `api_key_ciphertext`, `credential_revision`. Registros antigos recebem PROPERTIES/revisão 1. API key em claro e IAM token não são persistidos nas tabelas nem acrescentados ao JSON dos fluxos.

AES-256-GCM com nonce aleatório de 12 bytes e tag de 128 bits. Formato: `v1.<nonce-base64>.<ciphertext-e-tag-base64>`. Dados autenticados incluem portfólio, integração e endpoint: copiar ciphertext para outro vínculo ou adulterá-lo faz a autenticação falhar.

`IBM_CREDENTIAL_ENCRYPTION_KEY` contém 32 bytes em Base64, resolvidos por `ibm.watson.credentials-encryption-key`. No desenvolvimento é carregada pelo `.env` raiz como Java Properties. Uma chave aleatória foi criada no `.env` local, sem imprimir seu valor; o exemplo versionado contém apenas campo vazio.

Gere uma vez por instalação e preserve a chave. A restauração do banco depende da mesma chave, guardada separadamente do dump. Em produção, forneça-a por secret manager/secret do deploy. Não envie ao frontend nem versione. Alterar a chave mestra exige recriptografar ou recadastrar as API keys; não existe migração automática de chave mestra.

Ausência/chave inválida, adulteração ou contexto incorreto geram 503 `IBM_CREDENTIAL_ENCRYPTION_UNAVAILABLE`, sem fallback global. A aplicação pode usar integrações PROPERTIES sem chave mestra, mas não cadastrar/utilizar credenciais DATABASE nesse estado.

## Isolamento

`IbmClientFactory` resolve a conexão por ID de integração/revisão, com cache LRU de até 256 entradas. Cada integração DATABASE recebe transporte de endpoint fixo e seu próprio token provider IAM. Montagem do cliente tem lock curto; renovação IAM usa lock por conexão. Instâncias diferentes não compartilham token provider. Evicção pode provocar nova autenticação posterior.

O RestClient subjacente compartilha somente configuração de rede/TLS/timeouts, sem credenciais padrão. Endpoint/Bearer são passados explicitamente por cliente. Não há mutação do IbmSettings global, thread-local ou seleção de credenciais a partir do texto das mensagens.

Novas conversas e operações DATABASE persistem referência privada à integração/endpoint no binding SQL, sem API key/token. Alterar IDs de destino não redireciona sessões/reconciliações antigas. A chave atual do mesmo vínculo é usada após rotação. Encerramento/reconciliação podem resolver a conexão desativada, sob a autorização original dessas operações; novos envios continuam exigindo vínculo ativo.

O bloqueio de autoria usa endpoint/Assistant/skill, não apenas integração: portfólios apontando para o mesmo recurso físico continuam sujeitos ao bloqueio existente. Bindings legados sem referência usam PROPERTIES; não remova/reaponte a configuração legada enquanto houver sessões/operações dependentes dela.

## Bruno e validação

Escolha `01-Criar-integracao-isolada` e `07-Criar-destino-isolado` em `04-ibm`, em vez das alternativas legadas para o mesmo portfólio. Preencha `ibmServiceUrl`, `ibmApiKey`, IDs e `ibmNextApiKey` somente no ambiente local ignorado pelo Git. `13-Rotacionar-credencial` troca a chave DATABASE. Não há credenciais reais na collection.

`IbmPortfolioIsolationTests` simula duas instâncias/chaves, verificando cadastro OWNER, criptografia/AAD, sigilo, CSRF/escopo, URLs, cache/rotação/concorrência IAM, defaults, ausência de fallback, imports, sessão e autoria/reconciliação no endpoint correto. `IbmCredentialCipherTests` cobre nonces e autenticação contra adulteração/contexto/chave diferentes.

`LocalIbmCredentialStorageTests` passou com MySQL real: V7 aplicada, OWNER cadastrando, ciphertext persistido, descriptografia, rotação para revisão 2 e limpeza de suas fixtures. Usa chave IBM fictícia e não faz chamadas remotas. Logs: `target/ibm-credential-local.log` e `target/ibm-portfolio-isolation.log`.

```powershell
./mvnw.cmd '-Dtest=LocalIbmCredentialStorageTests' '-Dtabula.ibm.credentials.local.verify=true' test
```

Esta entrega não testa duas instâncias IBM reais, não migra automaticamente vínculos legados e não cria importação permanente. Permite realizar esse fluxo pelas APIs do produto com os dados da instância escolhida pelo usuário.

Validação final: 128 testes executados com zero falhas/erros e 14 casos opt-in pulados por padrão (142 registrados). Log: `target/ibm-isolated-full-suite.log`. O caso MySQL real foi executado separadamente com sucesso. A collection contém 76 requisições; a validação sintática do parser não equivale à execução de todas as requisições no aplicativo Bruno.
