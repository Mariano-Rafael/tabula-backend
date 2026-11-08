# Login e contas — implementação inicial

## Recursos implementados

O módulo `identity` possui controllers, serviços, DTOs, entidades e repositories. A persistência usa Spring Data JPA no MySQL, com schema criado pelo Flyway. MongoDB e IBM não participam da autenticação.

- Email único normalizado para minúsculas, sem espaços externos.
- Senhas armazenadas com BCrypt, salt individual e custo 12. Não há descriptografia.
- Sessão HTTP no servidor, sem JWT ou HTTP Basic.
- Login, logout, consulta da identidade, edição da própria conta e troca de senha.
- Criação e consulta de contas por administrador; atualização de nome, email, status e flag `admin`.
- Bloqueio da remoção/desativação do último administrador ativo, inclusive sob concorrência.
- Limite local de dez tentativas de login por email a cada quinze minutos; sucesso limpa o contador.

A flag `admin` representa administração global. Permissões por portfólio ainda serão implementadas. Não existe cadastro público.

## Preparar o MySQL e iniciar

Criar previamente um banco MySQL para o Tabula e um usuário com acesso ao schema. Flyway cria as tabelas `app_users`, `identity_guard` e sua tabela de migrations; não cria o banco nem usuários MySQL.

Configurar estas variáveis no ambiente ou na configuração de execução do IntelliJ:

| Variável | Valor/finalidade |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `identity` ou lista de perfis que inclua `identity` |
| `MYSQL_URL` | URL JDBC, por exemplo `jdbc:mysql://localhost:3306/tabula` |
| `MYSQL_USERNAME` | Usuário do banco |
| `MYSQL_PASSWORD` | Senha do banco |
| `SESSION_COOKIE_SECURE` | `true` por padrão; `false` apenas para desenvolvimento local em HTTP |
| `BOOTSTRAP_ADMIN_ENABLED` | `true` somente para inicializar a primeira conta |
| `BOOTSTRAP_ADMIN_NAME` | Nome do primeiro administrador; padrão `Administrador` |
| `BOOTSTRAP_ADMIN_EMAIL` | Email do primeiro administrador |
| `BOOTSTRAP_ADMIN_PASSWORD` | Senha inicial, fornecida externamente |

O profile está em `src/main/resources/application-identity.properties`. Também é possível fornecer diretamente as propriedades `spring.datasource.*` por configuração externa, sem usar esse profile.

Quando as configurações forem preenchidas no próprio `application.properties`, usar `spring.profiles.active=identity`. O nome `SPRING_PROFILES_ACTIVE` é destinado à variável de ambiente; não substitui a propriedade com pontos dentro do arquivo. As entradas `MYSQL_URL`, `MYSQL_USERNAME` e `MYSQL_PASSWORD` do arquivo são resolvidas pelos placeholders do profile. Manter valores reais fora dos commits.

Ao iniciar com bootstrap habilitado, a primeira conta será criada somente se a tabela de usuários estiver vazia. Se já existir qualquer conta, o bootstrap não altera senha, email ou privilégios. O bloqueio no banco serializa inicializações simultâneas. Após criar a conta, desabilitar o bootstrap e remover sua senha do ambiente de execução.

O teste administrativo `LocalAdminBootstrapTests` é opt-in por `-Dtabula.admin.bootstrap.verify=true`, usa a configuração local e executa o bootstrap real de inicialização. Diferentemente do smoke test MySQL, ele mantém a conta criada. Deve ser usado somente para provisionamento deliberado, com o bootstrap habilitado e os dados do administrador preenchidos. Não roda na suíte comum.

O administrador permanente foi provisionado no MySQL local e seu login com CSRF, sessão e privilégio administrativo foi validado. Após a validação, o bootstrap foi desabilitado e a senha inicial foi retirada do arquivo de configuração, mantendo apenas o placeholder. Email e senha não são registrados neste documento.

Não há senha administrativa padrão. Novas senhas precisam ter pelo menos 8 caracteres e no máximo 72 bytes UTF-8, limite do BCrypt. A regra se aplica ao cadastro, à troca de senha e ao bootstrap. Senhas com caracteres acentuados podem atingir o limite de bytes antes de 72 caracteres.

## Rotas disponíveis

| Método | Rota | Contrato |
|---|---|---|
| GET | `/api/v1/auth/csrf` | Público; retorna `headerName` e `token`, criando sessão quando necessário |
| POST | `/api/v1/auth/login` | JSON com `email` e `password`; requer CSRF; retorna conta |
| POST | `/api/v1/auth/logout` | Requer CSRF; invalida sessão e retorna 204 |
| GET | `/api/v1/auth/me` | Retorna a própria conta autenticada |
| PATCH | `/api/v1/account` | Campos opcionais `name` e `email` |
| PUT | `/api/v1/account/password` | `currentPassword` e `newPassword`; sucesso retorna 204 |
| POST | `/api/v1/admin/users` | `name`, `email`, `password`, `admin`; cria conta ativa, retorna 201 |
| GET | `/api/v1/admin/users/{id}` | Consulta administrativa por UUID |
| PATCH | `/api/v1/admin/users/{id}` | Campos opcionais `name`, `email`, `admin`, `active` |

Respostas de conta contêm somente `id`, `name`, `email`, `admin` e `active`. Entidades e hashes não são retornados. Edição própria não permite promover a conta ou alterar seu status.

Alterações usam transações e bloqueio de mutações de contas. Esta implementação ainda não expõe `ETag`/`If-Match`; o contrato geral de controle de revisão de rascunhos não está implementado nestas rotas.

## Sequência do cliente web ou Postman

1. Fazer `GET /api/v1/auth/csrf` e preservar o cookie de sessão.
2. Enviar `POST /api/v1/auth/login` com JSON e o token no header informado por `headerName`.
3. Preservar o cookie atualizado: o identificador da sessão é renovado no login.
4. Fazer novamente `GET /api/v1/auth/csrf`, pois o token anterior foi invalidado.
5. Usar o novo token e o cookie nos comandos de alteração e logout.

Exemplo de corpo de login, com dados fictícios:

```json
{"email":"admin@example.com","password":"uma-senha-longa-de-exemplo"}
```

Clientes devem enviar `Content-Type: application/json`. O token é o valor devolvido pelo endpoint; não gerar outro token no cliente. Requisições sem CSRF válido recebem 403, inclusive o login. O frontend futuro deverá preservar cookies (`credentials: 'include'` quando aplicável). CORS entre origens diferentes ainda não está configurado; a implantação inicial deve usar a mesma origem ou adicionar uma configuração explícita.

## Revogação e erros

Mudanças de senha e email invalidam imediatamente a sessão atual e incrementam a versão de autenticação da conta. Mudanças de privilégios/status também incrementam essa versão. Outras sessões são rejeitadas na próxima requisição, mediante consulta ao banco. Uma requisição que já passou pela validação pode terminar; não há cancelamento de trabalhos em andamento.

Login inválido e conta desativada retornam a mesma mensagem 401. Sem autenticação retorna 401; sem permissão ou CSRF retorna 403; validação retorna 400; email duplicado ou remoção do último administrador retorna 409; excesso de tentativas retorna 429. Erros tratados retornam `application/problem+json` com `code`, sem senha, valores rejeitados ou detalhes SQL.

## Validação e limites desta entrega

Os testes usam o contexto Spring, a cadeia real de segurança com MockMvc e H2 em modo MySQL. Aplicam a migration Flyway e verificam BCrypt, sessão, CSRF, acesso administrativo, unicidade, troca de senha, revogação, bootstrap e alterações concorrentes no último administrador.

A conexão e a migration foram validadas no MySQL local configurado pelo usuário em 2026-11-07. O teste `LocalMysqlIdentityTests` confirmou autenticação, sessão, CSRF, criação de conta e persistência BCrypt usando MockMvc e o banco real. As contas temporárias foram removidas e as contas preexistentes não foram alteradas. Essa validação não cria um administrador permanente nem verifica cookies em um navegador real.

O teste MySQL é opt-in e não executa na suíte comum. Para repeti-lo no banco local configurado:

```powershell
.\mvnw.cmd '-Dtest=LocalMysqlIdentityTests' '-Dtabula.mysql.verify=true' test
```

Esse comando aplica migrations pendentes, desabilita o bootstrap apenas no contexto do teste e cria contas com identificadores exclusivos, removidas em `finally`. A suíte comum continua usando H2 isolado; nunca apontar os testes gerais, que limpam suas próprias tabelas de teste, para um banco persistente.

Sessões e limitador de login ficam em memória nesta versão. Reinícios encerram sessões e zeram tentativas; múltiplas instâncias exigirão armazenamento compartilhado. Não foram implementados recuperação de senha por email, verificação de email, MFA, listagem paginada de usuários ou auditoria persistida. O restante da API documentada continua planejado.

Referências consultadas: [persistência da autenticação e gestão de sessões](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) e [inicialização de banco com Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
