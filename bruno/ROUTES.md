# Rotas da collection

Gerado de scripts/generate-bruno.cjs. Consulte README.md antes de executar.

| Pasta | Requisicao | Metodo | Rota |
|---|---|---|---|
| 01-auth | 01-CSRF | GET | /auth/csrf |
| 01-auth | 02-Login | POST | /auth/login |
| 01-auth | 03-Renovar-CSRF | GET | /auth/csrf |
| 01-auth | 04-Minha-conta | GET | /auth/me |
| 02-portfolios | 01-Criar | POST | /portfolios |
| 02-portfolios | 02-Listar | GET | /portfolios?page=0&size=20&includeArchived=false |
| 02-portfolios | 03-Consultar | GET | /portfolios/{{portfolioId}} |
| 02-portfolios | 04-Atualizar | PATCH | /portfolios/{{portfolioId}} |
| 02-portfolios | 05-Membros | GET | /portfolios/{{portfolioId}}/members?page=0&size=20 |
| 02-portfolios | 06-Adicionar-membro | POST | /portfolios/{{portfolioId}}/members |
| 02-portfolios | 07-Alterar-papel | PATCH | /portfolios/{{portfolioId}}/members/{{userId}} |
| 03-projetos | 01-Criar | POST | /portfolios/{{portfolioId}}/projects |
| 03-projetos | 02-Listar | GET | /portfolios/{{portfolioId}}/projects?page=0&size=20&includeArchived=false |
| 03-projetos | 03-Consultar | GET | /projects/{{projectId}} |
| 03-projetos | 04-Atualizar | PATCH | /projects/{{projectId}} |
| 04-ibm | 01-Criar-integracao | POST | /portfolios/{{portfolioId}}/integrations |
| 04-ibm | 01-Criar-integracao-isolada | POST | /portfolios/{{portfolioId}}/integrations |
| 04-ibm | 02-Listar-integracoes | GET | /portfolios/{{portfolioId}}/integrations |
| 04-ibm | 03-Consultar-integracao | GET | /integrations/{{integrationId}} |
| 04-ibm | 04-Atualizar-integracao | PATCH | /integrations/{{integrationId}} |
| 04-ibm | 05-Defaults-Draft | GET | /integrations/{{integrationId}}/defaults?environmentType=DRAFT |
| 04-ibm | 06-Testar-integracao | POST | /integrations/{{integrationId}}/connection-tests |
| 04-ibm | 07-Criar-destino | POST | /projects/{{projectId}}/targets |
| 04-ibm | 07-Criar-destino-isolado | POST | /projects/{{projectId}}/targets |
| 04-ibm | 08-Listar-destinos | GET | /projects/{{projectId}}/targets |
| 04-ibm | 09-Consultar-destino | GET | /targets/{{targetId}} |
| 04-ibm | 10-Atualizar-destino | PUT | /targets/{{targetId}} |
| 04-ibm | 11-Capacidades | GET | /targets/{{targetId}}/capabilities |
| 04-ibm | 12-Testar-destino | POST | /targets/{{targetId}}/connection-tests |
| 04-ibm | 13-Rotacionar-credencial | PUT | /integrations/{{integrationId}}/credentials |
| 05-fluxos | 01-Importar-DIALOG | POST | /projects/{{projectId}}/imports |
| 05-fluxos | 01-Importar-ACTION | POST | /projects/{{projectId}}/imports |
| 05-fluxos | 02-Upload | POST | /projects/{{projectId}}/flows |
| 05-fluxos | 03-Listar | GET | /projects/{{projectId}}/flows?page=0&size=20&includeArchived=false |
| 05-fluxos | 04-Consultar | GET | /flows/{{flowId}} |
| 05-fluxos | 05-Renomear | PATCH | /flows/{{flowId}} |
| 05-fluxos | 06-Versoes | GET | /flows/{{flowId}}/versions?page=0&size=20 |
| 05-fluxos | 07-Consultar-versao | GET | /versions/{{versionId}} |
| 05-fluxos | 08-Exportar | GET | /versions/{{versionId}}/export |
| 05-fluxos | 09-Reconciliar | POST | /versions/{{versionId}}/reconcile |
| 05-fluxos | 10-Iniciar-rascunho | POST | /flows/{{flowId}}/draft |
| 05-fluxos | 11-Ler-rascunho | GET | /flows/{{flowId}}/draft |
| 05-fluxos | 12-Salvar-rascunho | PUT | /flows/{{flowId}}/draft |
| 05-fluxos | 13-Snapshot | POST | /flows/{{flowId}}/versions |
| 05-fluxos | 14-Criar-layout | PUT | /flows/{{flowId}}/layout |
| 05-fluxos | 15-Ler-layout | GET | /flows/{{flowId}}/layout |
| 05-fluxos | 16-Salvar-layout | PUT | /flows/{{flowId}}/layout |
| 05-fluxos | 17-Diagrama-rascunho | GET | /flows/{{flowId}}/diagram |
| 05-fluxos | 18-Diagrama-versao | GET | /versions/{{versionId}}/diagram |
| 05-fluxos | 19-Editar-no-Dialog | PATCH | /flows/{{flowId}}/draft/dialog-nodes |
| 06-conversas | 01-Abrir | POST | /projects/{{projectId}}/conversations |
| 06-conversas | 02-Listar | GET | /projects/{{projectId}}/conversations?page=0&size=20 |
| 06-conversas | 03-Consultar | GET | /conversations/{{conversationId}} |
| 06-conversas | 04-Enviar-mensagem | POST | /conversations/{{conversationId}}/messages |
| 06-conversas | 05-Historico | GET | /conversations/{{conversationId}}/messages?page=0&size=20 |
| 06-conversas | 06-Consultar-turno | GET | /conversations/{{conversationId}}/messages/{{turnId}} |
| 06-conversas | 07-Reconciliar-turno | POST | /conversations/{{conversationId}}/messages/{{turnId}}/reconcile |
| 06-conversas | 08-Reconciliar-conversa | POST | /conversations/{{conversationId}}/reconcile |
| 06-conversas | 09-Encerrar | POST | /conversations/{{conversationId}}/close |
| 07-autoria-ibm-manual | 01-Preparar-Draft | POST | /projects/{{projectId}}/ibm-writes |
| 07-autoria-ibm-manual | 02-Consultar-operacao | GET | /ibm-writes/{{writeId}} |
| 07-autoria-ibm-manual | 02-Listar-operacoes | GET | /projects/{{projectId}}/ibm-writes?page=0&size=20 |
| 07-autoria-ibm-manual | 03-Backup-remoto | GET | /ibm-writes/{{writeId}}/backup |
| 07-autoria-ibm-manual | 04-Aplicar-Draft | POST | /ibm-writes/{{writeId}}/apply |
| 07-autoria-ibm-manual | 05-Reconciliar | POST | /ibm-writes/{{writeId}}/reconcile |
| 90-administracao-manual | 01-Criar-usuario | POST | /admin/users |
| 90-administracao-manual | 02-Consultar-usuario | GET | /admin/users/{{userId}} |
| 90-administracao-manual | 03-Atualizar-usuario | PATCH | /admin/users/{{userId}} |
| 90-administracao-manual | 04-Atualizar-minha-conta | PATCH | /account |
| 90-administracao-manual | 05-Alterar-minha-senha | PUT | /account/password |
| 99-encerramento-manual | Remover-membro | DELETE | /portfolios/{{portfolioId}}/members/{{userId}} |
| 99-encerramento-manual | Arquivar-fluxo | DELETE | /flows/{{flowId}} |
| 99-encerramento-manual | Desativar-destino | DELETE | /targets/{{targetId}} |
| 99-encerramento-manual | Desativar-integracao | DELETE | /integrations/{{integrationId}} |
| 99-encerramento-manual | Arquivar-projeto | DELETE | /projects/{{projectId}} |
| 99-encerramento-manual | Arquivar-portfolio | DELETE | /portfolios/{{portfolioId}} |
| 99-encerramento-manual | Logout | POST | /auth/logout |
| 05-fluxos | Criar-canvas-local | POST | /projects/{{projectId}}/local-flows |
| 05-fluxos | Salvar-canvas-local | PUT | /flows/{{flowId}}/draft |

## Editor atual — versões editáveis sem rascunho

Use a pasta `08-editor-versoes`. A criação retorna versionId da versão 1.

| Método | Rota `/api/v1` |
|---|---|
| POST | `/projects/{{projectId}}/flows` |
| GET | `/flows/{{flowId}}/versions` |
| GET / PUT | `/versions/{{versionId}}/canvas` |
| POST | `/flows/{{flowId}}/versions` (duplicar ou criar vazia) |
| PATCH | `/versions/{{versionId}}` |
| PUT | `/flows/{{flowId}}/ibm-binding` |
| POST | `/versions/{{versionId}}/ibm-import` |
| POST | `/versions/{{versionId}}/ibm-publications` (preparar substituição) |
| POST | `/flows/{{flowId}}/migrate-editor` |
