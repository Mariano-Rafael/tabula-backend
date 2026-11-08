# Contratos da API

Proposta para a API do Tabula. Contratos implementados estão em [Login e contas](10-login-e-contas.md), [Portfólios e projetos](11-portfolios-e-projetos.md), [Integração IBM](12-integracao-ibm-implementada.md) e [Fluxos](13-fluxos-importacao-e-versoes.md); as demais rotas desta página permanecem propostas. Prefixo `/api/v1` representa a versão da API Tabula e não a versão IBM.

Atendimentos, mensagens e histórico também estão implementados; seus contratos efetivos estão em [Atendimentos IBM v1/v2](14-atendimentos-ibm-e-historico.md).

## Convenções

- JSON em UTF-8; IDs UUID como strings; datas ISO 8601 em UTC.
- Paginação de listagens por `page` e `size`, com máximo configurado e ordenação estável por ID como desempate.
- Rascunhos e outros recursos editáveis usam `ETag` e `If-Match`. Revisão divergente retorna `412`; precondição obrigatória ausente retorna `428`.
- Criação síncrona retorna `201` e `Location`; operação demorada retorna `202` e URL de acompanhamento.
- `Idempotency-Key` obrigatório para mensagens, importações remotas e deployments; persistir escopo, hash e resultado. Definir prazo de retenção na implementação.
- Erros seguem `application/problem+json`, com `type`, `title`, `status`, `detail`, `instance`, `code` e `correlationId`. Nunca retornar stack trace ou segredo IBM.
- `400`: estrutura inválida; `401`: sem autenticação; `403`: sem permissão; `404`: inexistente ou invisível ao escopo; `409`: conflito; `422`: regra semântica inválida; `429`: limite local; `502/503/504`: falhas externas sanitizadas.

## Recursos principais

| Método | Rota | Finalidade |
|---|---|---|
| POST | `/auth/login` | Autenticar |
| POST | `/auth/logout` | Invalidar sessão |
| GET | `/auth/me` | Identidade e permissões |
| GET | `/auth/csrf` | Obter token CSRF para cliente web |
| GET, POST | `/portfolios` | Listar acessíveis ou criar |
| GET, PATCH, DELETE | `/portfolios/{id}` | Consultar, editar ou arquivar |
| GET, POST | `/portfolios/{id}/members` | Consultar ou adicionar membros |
| PATCH, DELETE | `/portfolios/{id}/members/{userId}` | Alterar papel ou remover membro |
| GET, POST | `/portfolios/{id}/projects` | Projetos do portfólio |
| GET, PATCH, DELETE | `/projects/{id}` | Consultar, editar ou arquivar projeto |
| GET, POST | `/portfolios/{id}/integrations` | Consultar ou cadastrar integração |
| GET, PATCH, DELETE | `/integrations/{id}` | Consultar sem segredo, editar ou desativar |
| POST | `/integrations/{id}/connection-tests` | Testar conectividade e autorização |
| GET, POST | `/projects/{id}/targets` | Gerenciar destinos IBM |
| GET | `/targets/{id}/capabilities` | Informar operações disponíveis |
| GET, POST | `/projects/{id}/flows` | Listar ou criar fluxo |
| GET, PATCH, DELETE | `/flows/{id}` | Metadados ou arquivamento |
| GET, PUT | `/flows/{id}/draft` | Ler ou substituir rascunho com revisão |
| POST | `/flows/{id}/validations` | Validar rascunho |
| GET, POST | `/flows/{id}/versions` | Listar ou criar snapshot |
| GET | `/versions/{id}` | Metadados da versão |
| GET | `/versions/{id}/export` | Exportar conteúdo autorizado |
| POST | `/projects/{id}/imports` | Importar JSON ou recurso remoto |
| POST | `/versions/{id}/deployments` | Publicar snapshot em destino |
| GET | `/operations/{id}` | Consultar estado de trabalho |
| POST | `/projects/{id}/conversations` | Abrir atendimento de teste ou operação |
| GET | `/conversations/{id}` | Consultar atendimento |
| POST, GET | `/conversations/{id}/messages` | Processar entrada ou consultar histórico |
| POST | `/conversations/{id}/close` | Encerrar e liberar sessão remota quando aplicável |
| GET | `/versions/{id}/diagram` | Obter projeção visual |
| GET, PUT | `/flows/{id}/draft/diagram-layout` | Consultar ou salvar posições visuais |

Gestão de contas de usuário será administrativa na primeira proposta, sem cadastro público. Contratos de criação, suspensão e recuperação de conta devem ser detalhados na etapa de identidade.

## Exemplo de mensagem

```http
POST /api/v1/conversations/{id}/messages
Content-Type: application/json
Idempotency-Key: 7c51a1d7-793f-4c50-96a9-6a4fe9a9ad97
X-CSRF-TOKEN: token-obtido-pelo-cliente

{"input":{"type":"text","text":"Olá"}}
```

```json
{
  "turnId": "turn-uuid",
  "sequence": 1,
  "status": "COMPLETED",
  "output": [{ "type": "text", "text": "Como posso ajudar?" }],
  "traceId": "trace-id"
}
```

O exemplo é ilustrativo. O cliente não escolhe credencial, contexto privilegiado ou engine a cada mensagem: esses dados são resolvidos a partir do atendimento autorizado. Saídas não textuais terão tipos próprios; preservar o payload original não implica expô-lo ao frontend.

## Operações assíncronas

Estados propostos: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`. `UNKNOWN` identifica efeito remoto não confirmado e exige reconciliação. `202` confirma aceitação local, não sucesso na IBM. Falhas retornam código estável, resumo sanitizado e possibilidade de nova tentativa segura.

O suporte inicial será HTTP e polling. Streaming, SSE ou WebSocket dependem de requisito posterior. Publicar OpenAPI e exemplos executáveis quando os controllers forem implementados.
