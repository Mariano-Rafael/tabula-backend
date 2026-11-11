# Prompt para iniciar no VS Code

Copie o texto abaixo para a nova conversa do agente no repositório frontend:

---

Estamos continuando o projeto Tabula em um repositório frontend separado. Leia primeiro AGENTS.md, docs/versoes-editaveis-e-vinculo-ibm.md, docs/contexto-do-projeto.md, docs/integracao-backend.md e docs/plano-inicial-frontend.md. O guia de versões editáveis prevalece sobre descrições anteriores do editor. Os guias do backend e a collection Bruno foram copiados para referência. Se o backend estiver no workspace, confira os controllers/DTOs atuais antes de assumir contratos.

Use React com TypeScript strict e Vite. A diretriz de stack inclui React Router, TanStack Query, Tailwind + shadcn/ui, React Hook Form + Zod e, posteriormente, Zustand e @xyflow/react para o editor. Confira as versões estáveis compatíveis ao iniciar.

Comece implementando a estrutura do frontend e a autenticação real: proxy /api para o backend, cliente Fetch com cookies e CSRF, login, restauração de sessão, área protegida e logout. Não use JWT/localStorage para autenticação. Depois entregue listagem/criação de portfólios integrada ao backend.

Use português do Brasil na interface e trate carregamento, vazio, erro, credenciais inválidas e sessão expirada. Não considere integração concluída com mocks. Execute as verificações do projeto e testes relevantes.

O backend Java/MySQL/MongoDB já existe. O editor agora salva versões editáveis completas, sem rascunho, com versão 1 na criação, duplicação, renomeação, versão vazia e vínculo IBM opcional/posterior. Importação e preparação da publicação têm rotas próprias. O backend possui tradutor inicial de Dialog clássico; siga seu contrato e trate erros de incompatibilidade. Não implemente runtime próprio, backend alternativo, tradutor paralelo no navegador ou publicação Live. As credenciais reais não estão neste pacote: obtenha acesso pelo login/telas apropriadas, sem copiar o .env do backend.

---

Se a API não estiver rodando, o agente pode continuar a estrutura e testes com fixtures identificadas, informando o que falta validar contra a API real. Acesso ao backend deve usar a cópia com as alterações locais documentadas, não assumir que somente o commit mencionado contém tudo.
