# Modelo de dados e consistência

Modelo lógico proposto. Os schemas efetivamente implementados estão nas migrations e nos documentos 10–13. Para os fluxos, consulte [Importação, versões e rascunhos](13-fluxos-importacao-e-versoes.md), incluindo o uso de rawJson e a reconciliação explícita sem worker automático nesta etapa.

## MySQL

Usar IDs UUID expostos como string, horários UTC e controle otimista de revisão nos registros mutáveis. Escolher a representação física dos UUIDs na migration inicial.

| Entidade | Campos principais e relações |
|---|---|
| User | id, email normalizado único, passwordHash, status |
| Portfolio | id, nome, status |
| PortfolioMember | portfolioId, userId, role; par único |
| Project | id, portfolioId, nome |
| Integration | id, portfolioId, provedor, endpoint, secretRef, status |
| IntegrationTarget | id, projectId, integrationId, ambiente, assistantId, workspaceId, skillId, configuração de versão da API |
| Flow | id, projectId, nome, tipo DIALOG ou ACTION, status |
| FlowVersion | id, flowId, número, documentId, schemaVersion, checksum, status, autor, origem, createdAt |
| Deployment | id, versionId, targetId, operationId, estado, referência remota |
| Conversation | id, portfolioId, projectId, versionId, engine, targetId opcional, sessão remota, estado |
| ConversationTurn | id, conversationId, sequência, idempotencyKey, documentId, estado |
| Operation | id, portfolioId, tipo, estado, tentativas, erro sanitizado |
| OutboxEvent | id, aggregateId, tipo, payload mínimo, estado, próxima tentativa |
| AuditEvent | id, portfolioId, ator, ação, recurso, resultado, correlationId, data |

Usar chaves estrangeiras para relações no MySQL. Impor unicidade de `(flowId, número)` e `(conversationId, sequência)`. Chaves de idempotência são únicas por escopo e associadas ao hash da requisição; reutilização com conteúdo diferente gera conflito.

Projetos, integrações e destinos usados em uma mesma operação devem pertencer ao mesmo portfólio. IDs fornecidos pelo cliente não substituem essa validação. A sessão remota, quando persistida, é informação operacional restrita.

## MongoDB

### flow_documents

```json
{
  "_id": "document-uuid",
  "portfolioId": "portfolio-uuid",
  "flowId": "flow-uuid",
  "versionId": "version-uuid",
  "schemaVersion": 1,
  "format": "IBM_DIALOG",
  "source": { "provider": "IBM", "apiFamily": "V1" },
  "rawPayload": {},
  "checksum": "sha256:...",
  "createdAt": "2026-11-07T00:00:00Z"
}
```

`rawPayload` preserva o documento importado e campos não reconhecidos. Remover credenciais e dados inadequados antes de persistir. Preservação é estrutural; se fidelidade byte a byte for necessária, guardar também o arquivo original em armazenamento apropriado. Definir canonicalização do JSON antes de calcular checksums.

### Outras coleções

- `flow_drafts`: conteúdo editável com revisão otimista e referência ao snapshot base.
- `diagram_layouts`: posições e agrupamentos visuais, associados a versão ou revisão de rascunho.
- `conversation_turns`: entrada, saída normalizada, referências ao payload IBM, sequência e métricas.
- `conversation_contexts`: contexto atual, revisão e último turno confirmado.

Índices devem começar pelos campos do escopo consultado, como `portfolioId`, seguidos de IDs e sequência. Impor unicidade de `versionId` nos documentos de versão e de `(conversationId, sequence)` nos turnos. Aplicar retenção por política; TTL não deve apagar snapshots necessários a atendimentos ativos ou auditoria.

Validar tamanho antes da gravação; documentos BSON têm limite de tamanho. Payloads acima da política da aplicação serão rejeitados ou externalizados com referência explícita, nunca truncados silenciosamente.

## Consistência entre bancos

Não assumir transação distribuída entre MySQL, MongoDB e IBM.

1. Em transação MySQL, reservar `FlowVersion` como `PENDING`, `documentId` e evento de outbox.
2. Worker grava documento MongoDB imutável usando o ID reservado, de forma idempotente.
3. Após confirmar conteúdo e checksum, marcar a versão `READY` no MySQL e concluir o evento.
4. Em falhas, repetir com o mesmo ID; o reconciliador detecta pendências e conteúdo órfão.

Versões `PENDING` ou `FAILED` não podem ser executadas ou publicadas. Snapshots `READY` são imutáveis; uma correção gera nova versão. O status de deployment é separado do status da versão, pois uma versão pode existir em vários ambientes.

Aplicar padrão equivalente a turnos e contexto. Serializar processamento por conversa; não avançar contexto antes de confirmar o turno. Não prometer exatamente uma execução de mensagem IBM quando houver timeout com resultado remoto desconhecido.

## Exclusão e restauração

Arquivar recursos referenciados pelo histórico em vez de apagá-los imediatamente. Exclusão definitiva exige política de retenção, limpeza coordenada e auditoria. Backups dos dois bancos precisam de manifesto de referências e procedimento de reconciliação após restauração.
