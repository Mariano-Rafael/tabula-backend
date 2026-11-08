# Collection Bruno e testes reais IBM

A collection está em [bruno](../../bruno/README.md), com inventário de rotas em [ROUTES.md](../../bruno/ROUTES.md). Contém login/CSRF, contas, portfólios, membros, projetos, integrações, destinos, fluxos, versões, rascunhos, conversas e histórico. Não contém credenciais reais.

Siga a preparação e a sequência do README da collection. Execute manualmente: há alternativas de fluxo e operações que alteram contas e arquivam recursos. As chaves de idempotência precisam ser mantidas em tentativas da mesma operação e substituídas para novas operações.

## Teste automatizado real

`RealIbmConversationTests` é opt-in e não usa mock do gateway IBM. Exige `IBM_TEST_MESSAGE` escolhido explicitamente para o ambiente Draft. Configuração e credenciais vêm do `.env` raiz. Executar pela raiz:

```powershell
$env:IBM_TEST_MESSAGE = 'mensagem aprovada para seu fluxo'
./mvnw.cmd '-Dtabula.conversation.ibm.verify=true' '-Dtest=RealIbmConversationTests' test
Remove-Item Env:IBM_TEST_MESSAGE
```

O teste verifica abertura, envio, replay com a mesma chave, histórico nos bancos e encerramento. As entidades locais criadas para o teste são removidas. Se o encerramento real falhar, a limpeza para com os metadados preservados para investigação. Não escreve corpos de mensagem, respostas, tokens ou credenciais no console.

V2 utiliza o assistant/environment Draft configurado e pode processar o Dialog incluído nesse Assistant. A rota V1 direta só é testada quando existe `IBM_DIALOG_WORKSPACE_ID`; skill ID não é substituto. Uma falha de envio deve ser investigada antes de repetir com nova chave, porque pode ter ocorrido processamento externo.

## Estado da validação

Em 08/11/2026, `LocalIbmReadTests` foi executado novamente contra a IBM real: autenticação IAM, listagem de workspaces V1 e leitura de ambiente/skills DIALOG e ACTION em Draft e Live passaram. A listagem de assistants V2 retornou `UNSUPPORTED / IBM_OPERATION_NOT_SUPPORTED`, como já observado no plano. Nenhum workspace Dialog V1 direto está configurado.

Os 64 arquivos `.bru` (62 requisições, configuração da collection e ambiente exemplo) passaram no parser oficial `@usebruno/lang` 0.41.0; os scripts também passaram na verificação sintática JavaScript. Isso não equivale a executar todas as rotas no aplicativo Bruno. O teste de conversa real compilou.

Em 08/11/2026, após autorização da mensagem `Primeira Action`, `RealIbmConversationTests` passou contra o ambiente Draft real em modo V2, com MySQL e MongoDB reais. Foram verificados abertura ACTIVE, mensagem COMPLETED, replay idempotente retornando o mesmo turno, histórico com apenas um turno e encerramento CLOSED. Os registros temporários foram removidos. O teste passou com 1 caso, zero falhas e zero erros; log local em `target/ibm-conversation-real.log`, sem corpos de resposta ou credenciais.

O modo V1 direto foi explicitamente pulado por ausência de `IBM_DIALOG_WORKSPACE_ID`. Essa alternativa legada não é necessária para executar Dialog clássico associado ao Assistant: esse cenário usa `mode=V2`, sem workspaceId, conforme a [documentação IBM](https://cloud.ibm.com/docs/watson-assistant?topic=watson-assistant-api-overview). Portanto, a ausência de workspaceId não bloqueia o cenário atual do projeto. O sucesso em V2 valida o caminho de conversa pelo Assistant, mas não demonstra qual skill/ramificação foi acionada nem valida todas as rotas HTTP da aplicação; o teste utiliza os serviços internos. As demais mensagens, cenários e publicação/edição remota não foram exercitados nesta execução.
