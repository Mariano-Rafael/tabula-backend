# Segurança e operação

Decisões propostas para implementação; não descrevem controles já ativos.

## Identidade e autorização

Para o primeiro frontend web próprio, usar sessão mantida no servidor pelo Spring Security. Cookie `HttpOnly`, `Secure` em HTTPS e `SameSite` definido conforme o domínio final. Manter proteção CSRF nas operações mutáveis, inclusive login e logout, e disponibilizar token ao cliente. Rotacionar o identificador ao autenticar e invalidar a sessão no logout.

Não adotar JWT apenas porque a aplicação possui API. Caso integrações externas ou SSO sejam necessárias, definir um contrato de autenticação próprio, preferencialmente com OAuth2/OIDC. Escala horizontal exigirá sessão compartilhada ou outra estratégia explícita; não presumir sessão local suficiente em múltiplas instâncias.

Senhas serão armazenadas exclusivamente como hash adaptativo com salt, usando encoder suportado pelo Spring Security. Configurar custo, política de troca e migração de hashes. Login deve retornar erro genérico e ter limitação de tentativas sem permitir bloqueio abusivo de contas.

| Papel por portfólio | Permissões propostas |
|---|---|
| OWNER | Administração e membros, além das demais operações |
| EDITOR | Projetos, rascunhos, importação e snapshots |
| OPERATOR | Atendimentos e testes de conversa |
| VIEWER | Leitura autorizada |

Publicação e exclusão remota exigem permissão específica `DEPLOY` ou `REMOTE_DELETE`, atribuível ao OWNER. Não permitir remover o último OWNER. Administração técnica global deve ser separada e auditada.

Toda busca por ID deve validar o portfólio autorizado, incluindo documentos MongoDB, operações, exports e diagramas. Papel e contexto de acesso vêm da identidade autenticada, nunca de campos confiados ao cliente.

## Credenciais e comunicação externa

- Preferir gerenciador de segredos e persistir `secretRef`; se criptografia em banco for necessária, chave mestra fica fora do banco e admite rotação.
- Receber segredo apenas em operações específicas; respostas indicam somente sua presença e metadados não sensíveis.
- Validar destinos IBM e política de saída para evitar SSRF: HTTPS, hosts aprovados, bloqueio de endereços internos e de redirecionamentos não autorizados.
- Separar credenciais por ambiente e aplicar menor privilégio.
- Configurar CORS com origens explícitas do frontend; não usar curinga com credenciais.
- Não executar código, templates arbitrários ou expressões Java provenientes de JSON importado.

## Dados e auditoria

Logs operacionais não devem conter senhas, tokens, cookies, chaves IBM ou transcrições completas. Redigir dados sensíveis em erros e traces. Acesso a conversas e exports exige autorização e auditoria.

Auditar ator, portfólio, ação, recurso, resultado, instante e correlation ID. Não copiar segredos para a auditoria. Definir retenção, anonimização e exclusão de dados pessoais antes da produção, considerando as obrigações aplicáveis ao negócio.

## Configuração proposta

| Configuração lógica | Origem |
|---|---|
| URL, usuário e senha MySQL | Ambiente/secret store |
| URI e credenciais MongoDB | Ambiente/secret store |
| Credenciais IBM | Secret store referenciado por integração |
| Data de versão e IDs IBM | Configuração do destino |
| Origens frontend e cookies | Configuração por ambiente |
| Timeouts, concorrência e payload máximo | Configuração operacional |

Os nomes concretos das variáveis serão definidos na implementação. Criar exemplos sem valores reais. Separar desenvolvimento, teste e produção. Não executar testes destrutivos contra destinos de produção.

## Observabilidade e recuperação

Adicionar health checks, métricas e tracing com IDs correlacionados. Proteger endpoints administrativos e detalhes de saúde. Medir latência e erros por operação/provedor, rate limits, fila, reconciliações, sessões expiradas e falhas de persistência. Evitar IDs de usuários ou conversas como labels de métricas.

Implementar outbox persistida e recuperação de trabalhos após reinício. Se houver múltiplos workers, usar claim atômico com lease, expiração e controle de concorrência. Limitar processamento simultâneo por integração e serializar turnos por conversa.

Documentar backup e restauração dos dois bancos, testar restauração e conciliar referências antes de liberar tráfego. Definir RPO/RTO com base na necessidade do produto. Deploys devem aplicar migrations compatíveis e não expor a aplicação antes da prontidão dos recursos obrigatórios.
