# Plano de implementação e validação

Este plano organiza entregas; não representa trabalho já concluído nem estimativa de prazo.

## Etapas

| Etapa | Entregáveis | Critério de conclusão |
|---|---|---|
| 0 — Contratos IBM | Exemplos sanitizados do projeto anterior, matriz por destino e fixtures | Operações de Dialogs e Actions mapeadas a contratos verificáveis |
| 1 — Fundação | Configuração dos bancos, migrations, identidade, sessão, autorização e erros | Login/logout, CSRF e isolamento por portfólio testados |
| 2 — Gestão | Portfólios, projetos, integrações, destinos e secrets | CRUD autorizado e credenciais ausentes de respostas/logs |
| 3 — Fluxos | Importação/exportação, rascunhos e snapshots | Ida e volta preserva JSON; concorrência e falhas entre bancos cobertas |
| 4 — Autoria IBM | Adaptadores v1/v2 aplicáveis, operações persistidas e publicação | Inclusão, alteração e exclusão comprovadas em ambiente de teste; resultado remoto verificado |
| 5 — Atendimentos | Engines IBM, sessões, turnos, contexto e histórico | Execução v1/v2, idempotência local, ordenação e sessão expirada testadas |
| 6 — Diagramas | Projeção visual e layout para React Flow | Referências e semântica preservadas; recursos desconhecidos diagnosticados |
| 7 — Runtime local | Interpretador de subconjunto explícito e limites | Corpus de compatibilidade e limitações publicados |
| 8 — Produção | Observabilidade, backup, restauração e deploy | Exercício de recuperação e controles de acesso validados |

A etapa 0 pode avançar junto da fundação. O frontend não é pré-requisito para testar os contratos HTTP.

## Estratégia de testes

- **Unidade:** regras de versão, autorização, transições de estado e condições do runtime.
- **Integração:** MySQL e MongoDB reais em ambiente isolado, migrations, índices e falhas parciais. Considerar Testcontainers quando houver runtime de containers disponível.
- **HTTP:** autenticação, CSRF, erros, paginação, controle otimista e idempotência.
- **Contrato IBM:** fixtures e servidor simulado para erros e timeouts; testes autenticados separados, opt-in e em destino de teste.
- **Preservação:** importar/exportar documentos representativos de Dialogs e Actions com campos desconhecidos.
- **Recuperação:** reinício entre gravações, worker duplicado, efeito remoto desconhecido e reconciliação.
- **Segurança:** acesso cruzado entre portfólios, exposição de segredos e validação de destinos externos.

O teste `contextLoads` atual é apenas um teste inicial de contexto; não comprova conectividade, segurança ou integração IBM.

## Critérios gerais de entrega

Cada funcionalidade deve incluir regras de acesso, validações, códigos de erro, tratamento de falhas e documentação atualizada. Acrescentar testes relevantes às regras e riscos da mudança. Manter a especificação OpenAPI coerente com os controllers implementados.

Não usar credenciais reais em fixtures. Testes que criam ou removem recursos IBM devem identificar e limpar somente recursos próprios do teste. Resultados incertos precisam ser reconciliados antes de repetir a operação.

## Riscos e respostas

| Risco | Resposta prevista |
|---|---|
| Diferenças entre API e produto IBM | Matriz por recurso e contratos reais |
| Sobrescrever edição feita na IBM | Detectar divergência e exigir resolução |
| Falha entre MySQL e MongoDB | IDs estáveis, outbox e reconciliação |
| Timeout após alteração remota | Estado UNKNOWN; consultar antes de repetir |
| Perda de campos na conversão visual | Preservar origem e testar ida e volta |
| Runtime local com semântica incompleta | Recusar recursos não suportados e publicar limites |
| Exposição de conteúdo de conversas | Autorização, redação e retenção definida |

## Próxima entrega sugerida

Implementar a fundação e o domínio de portfólios/projetos enquanto os contratos de Actions do projeto anterior são reunidos. Antes de desenvolver cada etapa, transformar os contratos propostos em critérios de aceite concretos e registrar ajustes necessários nesta documentação.
