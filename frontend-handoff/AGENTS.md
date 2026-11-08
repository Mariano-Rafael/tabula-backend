# Tabula frontend — instruções para continuidade

## Contexto obrigatório

Leia `docs/contexto-do-projeto.md`, `docs/integracao-backend.md` e `docs/plano-inicial-frontend.md` antes de implementar. Este repositório é o frontend React/TypeScript de um backend Java já existente. Consulte `reference/backend-docs` e `bruno` para contratos. Se o backend estiver no workspace, controllers/DTOs e testes atuais são a fonte de verdade.

A primeira entrega é estrutura e autenticação integradas à API real. Prossiga depois para portfólios, projetos, conexão IBM, destinos, importação e visualização do diagrama. O tradutor do editor e a edição visual completa foram adiados; não os trate como pré-requisito para as telas iniciais.

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
- Preserve ETags incluindo aspas: rascunho `"1"`, layout `"layout-1"`. Conflito 412 deve ser exibido e resolvido; não repita uma sobrescrita automaticamente com uma revisão nova.

## Editor

- O JSON IBM original é a fonte preservada no backend. O diagrama é parcial; nunca gere um JSON substituto com apenas os nós/arestas visíveis ou `data.original` serializado pelo navegador.
- IDs visuais `nodes[].id` servem para layout. `data.sourceId` é o ID IBM usado na edição limitada; `sourceRef` só vale na revisão de origem.
- Movimentar nós altera estado local; salvar layout envia apenas o contrato visual. Não envie `ReactFlowInstance.toObject()` inteiro ao endpoint de layout.
- Relações PARENT/EVALUATION_ORDER não significam execução incondicional. Não invente transições a partir de posição, ordem do array ou condições.
- Actions ainda mostram apenas nós de ação; passos internos e tradutor completo estão pendentes. `readOnly`/`editable` indicam capacidade do formato, não permissão do usuário.
- Aplicar uma versão no Draft IBM é uma ação explícita do usuário. Não apresente isso como publicação em Live.

## Desenvolvimento

- Priorize rotas reais; mocks só em testes/desenvolvimento explicitamente identificado. Não apresente mocks como integração concluída.
- Proteja navegação conforme sessão e permissões; durante restauração da sessão, use estado de carregamento para evitar redirecionamentos prematuros.
- Trate loading, vazio, erro, sessão expirada e conflito nas telas implementadas. Interface e mensagens em português do Brasil.
- Não invente endpoints de listagem de usuários, recuperação de senha, descoberta completa IBM, salvamento conjunto de diagrama/layout ou publicação Live: ainda não existem.
- Teste comportamentos relevantes: cookies/CSRF e sessão, erros estruturados, permissões, ETag, idempotência e fluxos críticos. Execute typecheck/build e verificações do projeto antes de concluir.
- Atualize a documentação quando um contrato/decisão mudar. Se houver uma lacuna na API, descreva-a com evidência do backend antes de propor mudança.
