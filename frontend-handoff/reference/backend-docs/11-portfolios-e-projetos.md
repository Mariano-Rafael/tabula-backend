# Portfólios e projetos — implementação inicial

## Organização

O módulo MVC `portfolio` contém controllers, serviços, DTOs, entidades e repositories JPA. Portfólios e projetos ficam no MySQL. A migration `V2__create_portfolios_and_projects.sql` cria `portfolios`, `portfolio_members` e `projects`, com chaves estrangeiras para portfólios e usuários e associação única por usuário/portfólio.

O módulo consulta identidade pelo contrato `AccountDirectoryService`, sem acessar repositories ou entidades internas de `identity`. Não há integração IBM, fluxo ou atendimento implementado nesta entrega.

## Regras de acesso

Qualquer conta ativa e autenticada pode criar um portfólio e se torna seu primeiro `OWNER`, na mesma transação. Um usuário comum só consulta portfólios dos quais é membro. Os projetos herdam as permissões do portfólio; não possuem membros próprios.

| Operação | OWNER | EDITOR | OPERATOR | VIEWER | Admin global |
|---|---|---|---|---|---|
| Consultar portfólio e projetos | Sim | Sim | Sim | Sim | Sim |
| Consultar membros (IDs e papéis) | Sim | Sim | Sim | Sim | Sim |
| Editar/arquivar portfólio | Sim | Não | Não | Não | Sim |
| Adicionar, remover ou alterar papel de membro | Sim | Não | Não | Não | Sim |
| Criar, editar ou arquivar projeto | Sim | Sim | Não | Não | Sim |

Em portfólios/projetos, `OPERATOR` possui leitura; suas operações de atendimento estão implementadas no [módulo de conversas](14-atendimentos-ibm-e-historico.md). A flag global `admin` concede acesso administrativo a todos os portfólios, inclusive sem associação, para supervisão e recuperação de acesso. Ela não altera o papel armazenado de um membro e não permite remover o último `OWNER`.

Não membros recebem 404 ao consultar ou modificar um recurso, evitando revelar sua existência. Membros sem a permissão necessária recebem 403. Ausência de autenticação recebe 401. Todas as alterações exigem o token CSRF já usado no login.

## Membros e propriedade

Adicionar membros por `userId` de uma conta existente e ativa. Associação duplicada retorna 409; conta inexistente ou desativada retorna 422. Os papéis são `OWNER`, `EDITOR`, `OPERATOR` e `VIEWER`.

Não é possível remover ou rebaixar o último membro com papel `OWNER`, inclusive para o administrador global. Para transferir a propriedade, primeiro adicionar/promover outro `OWNER` e depois remover/rebaixar o anterior. Um OWNER pode sair seguindo essa mesma regra. Permissões são consultadas a cada operação, sem depender de novo login após uma mudança de papel.

A proteção conta associações OWNER, não apenas contas ativas. Desativar uma conta não remove suas associações. Caso o único OWNER seja desativado, um administrador global pode adicionar outro OWNER ativo a um portfólio não arquivado. A desativação de contas continua sendo responsabilidade de `identity`.

## Rotas implementadas

| Método | Rota | Corpo/finalidade |
|---|---|---|
| POST | `/api/v1/portfolios` | Criar com `name` e `description` opcional; 201 e Location |
| GET | `/api/v1/portfolios` | Listar acessíveis; admin global lista todos |
| GET | `/api/v1/portfolios/{id}` | Consultar portfólio |
| PATCH | `/api/v1/portfolios/{id}` | Alterar `name` e/ou `description` |
| DELETE | `/api/v1/portfolios/{id}` | Arquivar; 204 |
| GET | `/api/v1/portfolios/{id}/members` | Listar membros paginados |
| POST | `/api/v1/portfolios/{id}/members` | Adicionar com `userId` e `role`; 201 |
| PATCH | `/api/v1/portfolios/{id}/members/{userId}` | Alterar `role` |
| DELETE | `/api/v1/portfolios/{id}/members/{userId}` | Remover associação; 204 |
| POST | `/api/v1/portfolios/{id}/projects` | Criar com `name` e `description` opcional; 201 e Location |
| GET | `/api/v1/portfolios/{id}/projects` | Listar projetos paginados |
| GET | `/api/v1/projects/{id}` | Consultar projeto |
| PATCH | `/api/v1/projects/{id}` | Alterar `name` e/ou `description` |
| DELETE | `/api/v1/projects/{id}` | Arquivar projeto; 204 |

IDs são UUIDs. Nomes devem conter texto e ter até 120 caracteres. Descrições aceitam até 1000 caracteres; omitir na criação produz string vazia. No PATCH, campos ausentes ou nulos mantêm o valor anterior; uma descrição vazia limpa o texto. Não é possível mover um projeto para outro portfólio por PATCH.

Listagens usam `page` (base zero) e `size` (1 a 100, padrão 20), com ordem estável por ID; membros são ordenados por userId. O resultado contém `content`, `page`, `size`, `totalElements` e `totalPages`. Parâmetros inválidos recebem 400.

## Exemplos de corpos

Criação de portfólio ou projeto:

```json
{"name":"Atendimento ao cliente","description":"Fluxos e projetos de atendimento"}
```

Adicionar membro, usando um UUID real da conta:

```json
{"userId":"6d6d50db-dac3-4f22-9ca7-f88e4e17b643","role":"EDITOR"}
```

O cadastro de usuários existente retorna o ID necessário. Busca/listagem administrativa de usuários e convites por email continuam fora desta entrega.

## Arquivamento

DELETE não apaga portfólios ou projetos do banco. Listagens excluem os arquivados por padrão; `includeArchived=true` inclui esses registros. Consulta direta continua disponível a usuários autorizados.

Portfólio arquivado bloqueia edição, alterações de membros e criação/edição/arquivamento de projetos. Projetos existentes são preservados e continuam legíveis; a flag `archived` própria de cada projeto não é alterada em cascata. Projeto arquivado também bloqueia edição. Repetir DELETE no portfólio é idempotente; repetir DELETE no projeto é permitido enquanto seu portfólio estiver ativo.

Não há restauração ou exclusão física pública nesta etapa. Um portfólio deve ter seus membros ajustados antes de ser arquivado.

## Concorrência e erros

Mutações usam transações `READ_COMMITTED` e bloqueio pessimista do registro do portfólio antes de consultar suas associações. Isso serializa mudanças de membros, arquivamento e alterações de projetos, permitindo reler permissões após espera pelo bloqueio, inclusive no MySQL. Entidades de portfólio/projeto também possuem versão JPA.

O controle atual serializa gravações, mas não detecta edição baseada em uma versão antiga enviada pelo cliente: `ETag`/`If-Match` ainda não está implementado nestas rotas. Erros tratados usam `application/problem+json` com códigos como `RESOURCE_NOT_FOUND`, `PORTFOLIO_PERMISSION_REQUIRED`, `LAST_PORTFOLIO_OWNER`, `RESOURCE_ARCHIVED` e `INVALID_PAGE`, sem detalhes SQL.

## Validação

`PortfolioIntegrationTests` usa H2 isolado, cadeia de segurança real e identidades de teste. Verifica os quatro papéis, isolamento de recursos, acesso global administrativo, validação, paginação, associação única, arquivamento, revogação de associação e preservação de um OWNER em alterações concorrentes.

`LocalMysqlPortfolioTests` é opt-in com `-Dtabula.mysql.portfolio.verify=true`. Usa a configuração MySQL local, aplica migrations e cria usuários, portfólio e projeto temporários para verificar persistência, concorrência de OWNERS e arquivamento. Limpa somente seus próprios registros e não altera contas existentes.

```powershell
.\mvnw.cmd '-Dtest=LocalMysqlPortfolioTests' '-Dtabula.mysql.portfolio.verify=true' test
```

O teste de bootstrap administrativo permanece desativado por padrão. As novas chaves estrangeiras não removem nem alteram o administrador permanente.

Na entrega inicial, a suíte isolada passou com 26 testes executados, sendo 12 casos de portfólios/projetos. A validação MySQL opt-in também passou, aplicando a migration V2 e confirmando que alterações concorrentes preservam um OWNER. Os registros temporários foram removidos; o administrador permanente foi preservado.
