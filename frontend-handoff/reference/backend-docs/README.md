# Documentação técnica — Tabula Backend

Data de referência: 2026-11-07.

O Tabula será uma plataforma de gestão de fluxos e atendimentos, inicialmente integrada ao IBM Watson/watsonx Assistant, com evolução para um motor próprio de conversas. O frontend será desenvolvido posteriormente e utilizará React Flow para visualizar e editar diagramas.

## Como ler

1. [Escopo e requisitos](01-escopo-e-requisitos.md)
2. [Arquitetura e stack](02-arquitetura-e-stack.md)
3. [Modelo de dados e consistência](03-modelo-de-dados.md)
4. [Integração IBM](04-integracao-ibm.md)
5. [Contratos da API](05-contratos-api.md)
6. [Segurança e operação](06-seguranca-e-operacao.md)
7. [Diagramas e runtime próprio](07-diagramas-e-runtime.md)
8. [Plano de implementação e validação](08-plano-de-implementacao.md)
9. [Módulos e responsabilidades](09-modulos-e-responsabilidades.md)
10. [Login e contas — implementação e execução](10-login-e-contas.md)
11. [Portfólios e projetos — implementação](11-portfolios-e-projetos.md)
12. [Integração IBM — configuração, destinos e leitura](12-integracao-ibm-implementada.md)
13. [Fluxos — importação, versões e rascunhos](13-fluxos-importacao-e-versoes.md)
14. [Atendimentos IBM v1/v2 e histórico](14-atendimentos-ibm-e-historico.md)
15. [Configuração local com .env](15-configuracao-local-env.md)
16. [Collection Bruno e testes reais IBM](16-collection-bruno-e-testes-ibm.md)
17. [Autoria IBM — atualização de skills Draft](17-autoria-ibm-draft.md)
18. [Layouts visuais para React Flow](18-layouts-visuais-react-flow.md)
19. [Projeção estrutural dos diagramas](19-projecao-de-diagramas.md)
20. [Instâncias IBM por portfólio](20-instancias-ibm-por-portfolio.md)
21. [Edição semântica de nós Dialog](21-edicao-semantica-dialog.md)
22. [Continuidade no frontend e VS Code](../../README.md)

23. [Atualizar Dialog clássico pelo Assistant V2](23-atualizar-dialog-classico-v2.md)
24. [Fluxos locais do canvas](24-fluxos-locais-canvas.md)

25. [Versões editáveis e vínculo IBM independente — contrato atual do editor](25-versoes-editaveis-e-vinculo-ibm.md)

## Status das decisões

- **Confirmado:** requisito explicitamente solicitado pelo usuário.
- **Proposto:** decisão técnica inicial, revisável durante a implementação.
- **Pendente:** depende de evidência, definição de produto ou validação na instância IBM.

Os documentos 01–09 descrevem majoritariamente o projeto pretendido. Os documentos 10–14 registram os contratos implementados de login, contas, portfólios, projetos, integração IBM, fluxos e atendimentos. Consulte as seções de validação para distinguir testes isolados, bancos reais e chamadas IBM reais; as demais funcionalidades permanecem planejadas.

## Estado observado no repositório

Os módulos `identity`, `portfolio`, `integration` e `flow` implementam login, contas, portfólios, membros, projetos, leituras IBM e importação/versionamento de fluxos. Metadados ficam no MySQL com Spring Data JPA e migrations Flyway; snapshots e rascunhos JSON ficam no MongoDB. Há testes isolados e validações reais opt-in, incluindo importação de Dialogs/Actions e gravação nos dois bancos. Consulte o documento 12 para as particularidades de acesso IBM, o documento 13 para fluxos e o documento 10 para banco e administrador inicial.

Os módulos `conversation` e `history` acrescentam atendimentos com orquestração IBM v1/v2, mensagens, contexto/sessão, histórico e reconciliação. Não há runtime próprio. Além dos testes com IBM simulada, o ciclo V2 Draft com mensagem real, histórico, idempotência e encerramento foi validado em 08/11/2026; V1 direto ainda depende de workspaceId. Detalhes e limitações estão nos documentos 14 e 16.

As versões registradas em [arquitetura e stack](02-arquitetura-e-stack.md) foram extraídas dos arquivos locais; não representam recomendação de atualização. A entrega inicial da documentação não incluiu execução; a implementação de identidade acrescentou testes automatizados sem acessar a conta IBM.

## Regras de manutenção

Atualizar a documentação junto de mudanças em contratos, dados, segurança ou capacidades de integração. Quando implementada, a especificação OpenAPI deverá refletir os contratos reais. Registrar decisões relevantes com contexto, escolha e consequências; não apresentar propostas como funcionalidades disponíveis.
