# Contexto do Tabula para o frontend

> Atualização do editor: consulte [versões editáveis e vínculo IBM](versoes-editaveis-e-vinculo-ibm.md). O novo modelo não usa rascunhos: salvar edita a versão, duplicar copia tudo, criar fluxo retorna a versão 1. Os detalhes antigos abaixo ficam como histórico quando conflitarem com o contrato novo.

Referência: 08/11/2026. O backend foi inspecionado na árvore local, incluindo alterações não commitadas. Não assuma que só clonar seu HEAD reproduz todos os recursos descritos.

## Produto e decisões

Tabula é uma plataforma de gestão de fluxos e atendimentos integrada ao IBM Watson/watsonx Assistant. Backend Java separado; frontend será outro repositório com TypeScript/React. O editor utilizará React Flow.

O fluxo de produto é: usuário entra, cria portfólio/projeto, vincula uma instância IBM, configura Assistant/ambiente/skills, solicita importação pela API Tabula e visualiza o conteúdo. O usuário não precisa entregar um JSON manual para a importação normal; upload é alternativa existente.

Processamento das conversas permanece na IBM. Não há runtime próprio. Dialog clássico associado a um Assistant é atendido pela API V2 sem workspaceId. O modo V1 direto existe como alternativa legada e só ele exige workspaceId; não exigir esse ID no fluxo comum V2.

## Backend implementado

Java 21, Spring Boot/MVC, Spring Security, sessão HTTP e CSRF, JPA/MySQL/Flyway, MongoDB, Maven. Módulos: identity, portfolio, integration, flow, conversation e history.

| Área | Disponível |
|---|---|
| Identidade | Email/senha, BCrypt, perfil, troca de senha, flag admin e gestão de usuário por ID |
| Organização | Portfólios, membros/papéis, projetos e arquivamento |
| IBM | Uma integração por portfólio, credenciais próprias criptografadas, IAM separado, probes, rotação e destinos |
| Fluxos | Imports DIALOG/ACTION pela IBM, upload, versões imutáveis, exportação, rascunho e reconciliação |
| Visual | Layout separado e projeção estrutural de Dialog; visão parcial de Actions |
| Edição limitada | Título, condição e textos literais existentes em nó Dialog standard, com revisão |
| Autoria | Preparar/aplicar/reconciliar conteúdo de skill existente no Draft, com backup e controle de operação |
| Conversas | Sessão IBM, mensagem, idempotência, estados, histórico e encerramento |

Migrations V1–V7 abrangem identidade, organização, integrações/destinos, fluxos/outbox, conversas e credenciais por portfólio. Não há dados iniciais obrigatórios de portfólio/projeto; criá-los faz parte da aplicação.

## Persistência

MySQL guarda identidade, escopo, metadados, versões, destinos, operações e ciphertext de credenciais. MongoDB guarda:

- `flow_documents`: JSON completo de cada skill/versão, imutável e com checksum.
- `flow_drafts`: JSON completo do rascunho atual, revisão independente.
- `flow_layouts`: posições, grupos, dimensões preferidas e viewport, com revisão própria.
- `ibm_skill_write_documents`: conteúdo anterior/candidato das operações de autoria.
- `conversation_documents`: mensagens/respostas e contexto operacional, com acesso controlado.

O snapshot importado preserva a skill completa selecionada, não o envelope integral do Assistant nem outras skills. Imports podem reserializar o conteúdo, preservando estrutura e precisão, mas não espaços byte a byte. Layout não entra no payload IBM. Uma versão de conteúdo ainda não contém snapshot histórico do layout.

## Papéis

Admin global é uma propriedade da conta; OWNER/EDITOR/OPERATOR/VIEWER são papéis por portfólio. Um usuário ativo pode criar portfólio e se torna OWNER.

- OWNER/admin gerencia membros, vínculo IBM, credenciais e destinos.
- OWNER/EDITOR/admin gerencia projetos, fluxos, rascunhos/layouts e autoria.
- OPERATOR também opera conversas; VIEWER tem leitura.
- Não membros recebem 404; falta de permissão interna costuma retornar 403.

PortfolioView não traz o papel do usuário. Ele pode ser obtido pelos membros paginados, cruzando `userId` com `/auth/me`. Não confunda `admin` com OWNER ou derive a permissão de uma flag visual do diagrama. O backend é a autoridade de acesso.

## Pendências e escolhas recentes

O usuário decidiu começar o frontend antes do tradutor e do editor completo. Criação/exclusão/conexão de nós acontecerá localmente no editor; um contrato de salvamento e um tradutor backend serão implementados depois. O tradutor deverá combinar fonte preservada e alterações para gerar JSON IBM completo. **Esse contrato unificado e esse tradutor ainda não existem.**

Também pendentes: passos internos de Actions, edição estrutural ampla, releases/publicação Live, histórico visual por versão, descoberta completa dos recursos IBM para seleção, recuperação de senha, listagem/busca de usuários e recursos operacionais de filas/transferência/SLA. Não apresentar essas telas como funcionais com endpoints inventados.

Existe PATCH limitado de nó já implementado; ele pode ser usado posteriormente para os campos suportados, mas não obriga chamadas a cada interação. A primeira versão do editor no frontend deve focar em visualização/movimentação e salvamento do layout.

## Evidência e limites

Última suíte registrada: 133 testes executados sem falhas, 14 casos opt-in pulados (147 totais). Há verificações separadas com MySQL/MongoDB reais e testes IBM reais de leitura/importação, conversa V2, atualização/restauração de títulos e ciclo de criação/exclusão/recriação de um nó Dialog temporário. Conteúdo original foi restaurado e fixtures removidas nesses testes.

O isolamento de duas instâncias foi validado com simulação; a persistência criptografada/rotação também passou em MySQL real. Não afirmar testes reais contra duas instâncias distintas. A listagem geral de assistants respondeu como indisponível no plano configurado, enquanto operações específicas funcionaram. O fluxo de negócio não pode depender dessa listagem existir.

Aplicar conteúdo ao Draft substitui o workspace da skill existente; não é publicação em Live. Um 202 ou POST aceito não comprova conclusão. Não há worker automático de reconciliação nem runtime próprio.
