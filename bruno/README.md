# Tabula — testes manuais no Bruno

Abra esta pasta em **Open Collection** no Bruno. A collection contém 76 requisições, com corpos, testes de status e captura de variáveis. O inventário completo está em [ROUTES.md](ROUTES.md).

## Preparação

Para conexões próprias por portfólio, escolha `01-Criar-integracao-isolada` e `07-Criar-destino-isolado` em `04-ibm`. As alternativas com mesmo prefixo não devem ser executadas juntas. Configure `ibmServiceUrl`, `ibmApiKey`, `ibmAssistantId`, `ibmEnvironmentId` e skills no ambiente local. OWNER pode cadastrar a própria conexão; o vínculo legado properties exige administrador global. Defaults de uma conexão isolada não copiam IDs do servidor. Use `13-Rotacionar-credencial` somente quando quiser substituir a API key, preenchendo `ibmNextApiKey`. Consulte [o contrato](../docs/20-instancias-ibm-por-portfolio.md).

1. Copie `environments/Local.example.bru` para `environments/Local.bru` e selecione `Local` no Bruno. Preencha `email` e `password` com uma conta existente. Nunca coloque a API key IBM na collection: a aplicação usa o `.env` do servidor.
2. Inicie MySQL, MongoDB e o backend a partir da raiz do projeto. Para HTTP local, defina `SESSION_COOKIE_SECURE=false` no ambiente do processo; em HTTPS mantenha `true`. O backend importa o `.env` raiz como Java Properties.
3. Mantenha o cookie jar do Bruno habilitado. A autenticação usa `JSESSIONID`, sem Bearer token.
4. Execute `01-auth` em ordem: CSRF, login, novo CSRF e minha conta. O login renova sessão e token. Os scripts guardam o token somente em memória e adicionam o cabeçalho nas operações de escrita.

## Sequência de trabalho

Execute requisições individualmente. Não execute a collection inteira: existem alternativas de fluxo, mudanças de senha, arquivamentos e chamadas reais IBM. Os testes de status aceitam respostas assíncronas e falhas de turno previstas pelo contrato; isso não significa que a operação terminou com sucesso. Inspecione também `state`, `checks`, `code` e `errorCode`.

- `02-portfolios` e `03-projetos`: crie portfólio e projeto; IDs são capturados. Para adicionar um membro, crie antes um usuário na pasta `90-administracao-manual`. Não use o administrador permanente como usuário de teste.
- `04-ibm`: crie a integração com administrador global, consulte defaults Draft e crie o destino. O JSON de configuração é capturado sem credenciais. O PUT substitui a configuração inteira. Os probes podem responder HTTP 200 com `successful=false`; a listagem de assistants pode ser indisponível no plano IBM mesmo com recursos específicos acessíveis.
- `05-fluxos`: escolha importação DIALOG, ACTION ou upload. Cada resposta passa a definir o fluxo/versão corrente. Se a exportação IBM retornar `202 IBM_EXPORT_PENDING`, repita a mesma requisição e chave após o intervalo indicado. Use somente versão `READY` para exportar/iniciar rascunho. O exemplo de edição é um Dialog vazio: substitua-o pelo JSON completo desejado. O ETag capturado inclui aspas e alimenta `If-Match`. Uma revisão antiga retorna 412; ausência retorna 428. Snapshot cria versão local e não publica na IBM.
- `06-conversas`: use `mode=V2` para o Assistant com Dialog/Actions no ambiente Draft, inclusive o Dialog clássico chamado de “v1 dentro da v2”. Esse cenário não exige workspaceId: o modo representa a API usada, não o tipo de skill. Defina uma mensagem segura antes de enviar: o diálogo pode executar integrações externas. Abra, envie, consulte histórico e encerre. Somente a alternativa legada de chamada direta `mode=V1` requer `dialogWorkspaceId` válido no destino; `dialogSkillId` não substitui esse ID. Veja a [visão geral oficial IBM](https://cloud.ibm.com/docs/watson-assistant?topic=watson-assistant-api-overview).
- `07-autoria-ibm-manual`: prepare a aplicação de uma versão READY a uma skill existente DRAFT, consulte a operação e baixe o backup. Preparação não altera IBM. Revise o JSON da versão via exportação local. **Apply altera o conteúdo remoto**; execute só após revisar destino/versão. Reconcile verifica o resultado sem reenviar. Troque `writeKey` para uma nova preparação; não troque para contornar UNKNOWN. Veja [contratos e limitações](../docs/17-autoria-ibm-draft.md). Se tiver um ambiente Local anterior, acrescente `writeKey` manualmente.
- `90-administracao-manual`: criação/consulta de usuário e alterações de conta. Troca de senha invalida sessões; alteração de email também encerra a sessão. Autentique novamente quando necessário.
- `99-encerramento-manual`: encerramento de sessão e alterações de estado/arquivamentos. Execute apenas nos recursos de teste escolhidos.

## Idempotência e concorrência

Para layouts, use `14-Criar-layout`, `15-Ler-layout` e `16-Salvar-layout` em `05-fluxos`. O primeiro PUT exige `If-None-Match: *`; os demais usam o ETag `layout-N` capturado em `layoutEtag`. As revisões visuais são independentes de `draftEtag`. Salvar posições/grupos/viewport não altera o conteúdo IBM nem cria elementos de diálogo. Veja [o contrato do layout](../docs/18-layouts-visuais-react-flow.md).

`17-Diagrama-rascunho` e `18-Diagrama-versao` retornam a projeção estrutural e o layout atual. Use os IDs retornados em `nodes[].id` nas posições do layout. Rascunho precisa ser inicializado; versão precisa estar READY. Inspecione `diagnostics`; Actions ainda não exibem passos internos. A projeção é somente leitura e não aciona IBM. Veja [o contrato do diagrama](../docs/19-projecao-de-diagramas.md).

Troque cada chave `*-CHANGE-ME-001` antes de iniciar um novo cenário. Mantenha a mesma chave e o mesmo corpo ao repetir uma operação incerta. Nova conversa, nova mensagem, nova importação/upload e novo snapshot exigem novas chaves. A mesma chave com corpo diferente retorna conflito. Não gere automaticamente uma chave nova ao reenviar: isso pode duplicar efeitos na IBM.

Mensagens em `UNKNOWN` e conversas em estados incertos exigem inspeção/reconciliação. A reconciliação não reenvia mensagens. Não presuma que 202 confirma processamento. Ao sair do Bruno, as variáveis capturadas em memória podem ser perdidas; consulte listagens e preencha os IDs novamente, sem recriar recursos por engano.

Na autoria Draft, `READY` com `errorCode=IBM_RATE_LIMITED` significa que a leitura anterior ao envio foi adiada: aguarde antes de repetir apply da mesma operação. Se já estiver `PROCESSING`/`UNKNOWN`, use reconcile, sem criar outra escrita. A validação real encontrou rate limit na exportação usada para confirmar o conteúdo; aguardar e consultar novamente permitiu confirmar e restaurar o Dialog.

## Manutenção e validação

`node scripts/generate-bruno.cjs` regenera as requisições e o inventário. O gerador não lê credenciais. Não edite arquivos gerados esperando preservar alterações após regeneração; mantenha exemplos pessoais em outra pasta.

Os arquivos locais de ambiente são ignorados pelo Git. Não exporte cookies, respostas privadas ou credenciais ao compartilhar a collection. A sintaxe dos scripts segue a [referência oficial do Bruno](https://github.com/usebruno/bruno-docs/blob/main/testing/script/javascript-reference.mdx).

O teste automatizado opt-in `RealIbmConversationTests` usa IBM real e os dois bancos, cria registros temporários, envia uma mensagem por modo disponível, verifica replay idempotente/histórico e encerra a sessão. Requer `IBM_TEST_MESSAGE` definido explicitamente e `-Dtabula.conversation.ibm.verify=true`. Sem workspace válido, registra V1 como não testado. Não valida todas as ramificações do diálogo nem publicação/edição remota.
