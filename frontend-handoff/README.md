# Tabula frontend — pacote de continuidade

Preparado em 08/11/2026 a partir da árvore local de `tabula-backend`. Este pacote transfere decisões e contratos; não cria o frontend nem transporta automaticamente o histórico da conversa.

## Levar ao novo repositório

1. Crie/abra o repositório `tabula-frontend` no VS Code.
2. Copie **o conteúdo desta pasta** para a raiz dele, preservando os nomes: `AGENTS.md`, `.env.example`, `.gitignore`, `docs/`, `reference/` e `bruno/`. Se já houver arquivos com esses nomes, incorpore o conteúdo sem sobrescrever suas regras existentes.
3. Leia [o contexto](docs/contexto-do-projeto.md), [a integração](docs/integracao-backend.md) e [o plano inicial](docs/plano-inicial-frontend.md).
4. Inicie uma nova conversa com [o prompt de continuidade](docs/prompt-para-vscode.md).
5. Opcional: copie `tabula.code-workspace.example` para `tabula.code-workspace` e ajuste o caminho do backend. Ele permite abrir os dois repositórios no mesmo workspace.

O backend original estava no HEAD `d7968f4`, **com alterações locais ainda não commitadas**. Os contratos deste pacote refletem essa árvore local, não apenas aquele commit. Para consultar/executar o backend, utilize uma cópia que contenha as implementações descritas. Este pacote não copia código Java nem cria commits.

## Conteúdo

| Arquivo/pasta | Uso |
|---|---|
| `AGENTS.md` | Instruções para o agente no repositório frontend |
| `docs/contexto-do-projeto.md` | Produto, decisões, estado real e pendências |
| `docs/integracao-backend.md` | Autenticação, contratos, formatos, estados e exemplos |
| `docs/plano-inicial-frontend.md` | Stack, organização, proxy e primeira entrega |
| `docs/prompt-para-vscode.md` | Texto pronto para abrir a nova conversa |
| `.env.example` | Somente a URL relativa pública da API |
| `bruno/` | Collection de referência com 77 requisições e ambiente fictício |
| `reference/backend-docs/` | Guias implementados 10–21 e documentos de planejamento 01–09 |
| `reference/rotas-backend.md` | Inventário das rotas da collection |

Os guias copiados em `reference/backend-docs` são fotografias de entregas anteriores: alguns números de testes/escopos retratam a data daquela entrega. Prefira o contexto deste pacote, os guias específicos mais recentes e, quando acessível, os controllers/DTOs do backend. Não há especificação OpenAPI publicada ainda.

## Ambientes

O backend deve estar rodando com MySQL/MongoDB e sua configuração própria. No frontend, copie `.env.example` para `.env.local`. Use um proxy Vite `/api` apontando ao backend; instruções em [plano inicial](docs/plano-inicial-frontend.md).

Não leve o `.env` do backend, dumps, cookies, credenciais IBM, chave mestra, senhas ou logs. Variáveis `VITE_*` são públicas no bundle. As credenciais reais serão fornecidas pelo usuário nas telas apropriadas e enviadas apenas à API Tabula.

A collection é para execução manual por cenário. Não execute tudo de uma vez: ela contém troca de senha, arquivamentos, envio de mensagens e aplicação de conteúdo na IBM. Os exemplos de ambiente estão vazios ou usam placeholders.
