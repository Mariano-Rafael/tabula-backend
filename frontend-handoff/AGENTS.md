# Tabula frontend — instruções para continuidade

## Contexto obrigatório

Leia primeiro `docs/versoes-editaveis-e-vinculo-ibm.md`: esse contrato substitui as instruções históricas de rascunho, snapshot imutável e layout separado abaixo. Novos fluxos têm versões editáveis, criam/abrem a versão 1, permitem duplicação integral, renomeação e versão vazia. O vínculo IBM é independente do canvas; o tradutor inicial de Dialog clássico já existe. Não use o ciclo legado para telas novas.

Leia `docs/contexto-do-projeto.md`, `docs/integracao-backend.md` e `docs/plano-inicial-frontend.md` antes de implementar. Este repositório é o frontend React/TypeScript de um backend Java já existente. Consulte `reference/backend-docs` e `bruno` para contratos. Se o backend estiver no workspace, controllers/DTOs e testes atuais são a fonte de verdade.

A primeira entrega é estrutura e autenticação integradas à API real. Prossiga depois para portfólios, projetos, conexão IBM, destinos e editor de versões. O backend agora tem salvamento integral de canvas e tradutor inicial de Dialog clássico; Actions e conversão universal continuam pendentes.

## Stack e organização

- React + TypeScript strict + Vite, SPA, com React Router.
- TanStack Query para estado remoto e mutations; Zustand para estado local compartilhado do editor, quando necessário. Não duplique o mesmo estado nas duas camadas.
- Tailwind CSS + shadcn/ui; React Hook Form + Zod; React Flow via `@xyflow/react`.
- Fetch encapsulado para HTTP; Vitest e Playwright conforme os fluxos implementados.
- Organize por funcionalidades em `src/features`; cliente HTTP em `src/shared/api`; rotas/providers em `src/app`.
- Ao instalar, confira versões estáveis e compatibilidade no ambiente atual; mantenha um único gerenciador/lockfile. Não fixe versões copiadas da memória desta conversa.

## Integração e segurança

- API base `/api/v1` por proxy no desenvolvimento. Use cookies com `credentials: 'include'`. A autenticação não usa JWT/Bearer do usuário.
- Busque CSRF antes de login; envie o cabeçalho retornado; busque um novo token após login. Logout também exige CSRF. Token CSRF em memória; JSESSIONID é administrado pelo navegador.
- Trate 401 como sessão ausente/expirada e 403 como acesso/CSRF, sem encerrar indiscriminadamente sessões em 403.
- Não copie o `.env` do backend para o frontend. Nunca coloque API key IBM, senhas, chave mestra ou credenciais de banco em `VITE_*`, storage persistente, fixtures, logs ou mensagens de erro.
- A UI recebe API key para cadastrar/rotacionar uma integração, mas limpa o formulário após envio e não a mantém em stores persistidos/cache persistido. O backend não devolve a chave cadastrada.
- Não chame IBM diretamente do navegador. Criação de portfólio/vínculo/importação ocorre pela API Tabula.
- Crie uma chave de idempotência por intenção e mantenha-a no reenvio da mesma intenção/corpo. Não configure retry automático de mutations que possam enviar mensagens ou aplicar conteúdo remoto.
- Respostas 202 podem ser ProblemDetail, não o DTO esperado. Verifique content-type, `code` e `state`, inclusive em status 2xx.
- Preserve ETags incluindo aspas: versão `"1"`. Conflito 412 deve ser exibido e resolvido; não repita uma sobrescrita automaticamente com uma revisão nova.

## Editor

- Criar fluxo retorna flowId/versionId da versão 1; navegue diretamente para ela. Ofereça vínculo IBM opcional, que também pode ser cadastrado depois.
- Use `/versions/{id}/canvas` para ler/salvar documento completo. Não crie rascunho nem use layout separado para novos fluxos.
- Salvar altera a versão selecionada. Duplicar copia conteúdo, posições, conexões e variáveis no servidor. Nova versão sem sourceVersionId nasce vazia. Permita renomear com ETag.
- Campo ibm preserva a skill original importada. Preserve precisão numérica com parser lossless ao modificar esse conteúdo no navegador.
- Carregar da IBM e publicar são ações explícitas, independentes do vínculo. Avise que importar substitui a versão local e publicar substitui o workspace remoto no Draft.
- Use o contrato inicial de nós text/ibmDialog standard, conditions, text e relações PARENT/EVALUATION_ORDER/JUMP do guia atual. Estruturas incompatíveis retornam 422; não simule sucesso nem descarte elementos.
- Preparar publicação congela o conteúdo. Depois use apply/reconcile existentes. Não apresente atualização Draft como implantação Live.

## Desenvolvimento

- Priorize rotas reais; mocks só em testes/desenvolvimento explicitamente identificado. Não apresente mocks como integração concluída.
- Proteja navegação conforme sessão e permissões; durante restauração da sessão, use estado de carregamento para evitar redirecionamentos prematuros.
- Trate loading, vazio, erro, sessão expirada e conflito nas telas implementadas. Interface e mensagens em português do Brasil.
- Não invente endpoints de listagem de usuários, recuperação de senha, descoberta completa IBM ou publicação Live: ainda não existem.
- Teste comportamentos relevantes: cookies/CSRF e sessão, erros estruturados, permissões, ETag, idempotência e fluxos críticos. Execute typecheck/build e verificações do projeto antes de concluir.
- Atualize a documentação quando um contrato/decisão mudar. Se houver uma lacuna na API, descreva-a com evidência do backend antes de propor mudança.
