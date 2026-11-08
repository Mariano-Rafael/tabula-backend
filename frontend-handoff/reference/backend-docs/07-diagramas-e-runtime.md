# Diagramas e runtime próprio

## Separação de representações

Manter três representações distintas:

1. **Documento de origem:** JSON IBM preservado, fonte para exportação e auditoria.
2. **Modelo semântico:** interpretação versionada dos elementos reconhecidos pelo Tabula.
3. **Diagrama:** nós, arestas, grupos e posições consumidos pelo frontend React Flow.

React Flow pertence ao frontend. O backend fornece um contrato JSON estável e não depende da biblioteca React.

## Contrato visual proposto

```json
{
  "schemaVersion": 1,
  "flowVersionId": "version-uuid",
  "nodes": [{
    "id": "node-estavel",
    "type": "condition",
    "position": { "x": 100, "y": 80 },
    "data": {
      "label": "Condição",
      "sourceRef": "/dialog_nodes/0",
      "editable": false
    }
  }],
  "edges": [],
  "diagnostics": []
}
```

O exemplo mostra apenas a forma do contrato; não define a conversão de um nó IBM real. IDs devem ser estáveis e distintos de posições em arrays. `sourceRef` identifica a origem na revisão exata; ao editar ou reordenar o JSON, recalcular o mapeamento e validar a revisão base.

A projeção precisa representar ordem de avaliação, transições, condições, agrupamentos e destinos ausentes. Elementos não interpretados permanecem no JSON e aparecem com diagnóstico e edição restrita. Um diagrama parcial não pode ser exportado como se representasse o fluxo completo.

Mover um nó altera apenas layout. Alterações semânticas geram comandos validados sobre o rascunho e exigem `If-Match`; não substituir todo o documento original por um JSON montado apenas com os nós visíveis.

## Validações de conversão

- Integridade de referências e unicidade de IDs.
- Ordem e prioridade de transições preservadas.
- Preservação de campos desconhecidos em ida e volta.
- Diagnóstico de ciclos, caminhos ausentes e recursos não suportados.
- Comparação semântica entre snapshot original e exportação, descontando apenas normalizações documentadas.

## Contrato de execução

```java
public interface ConversationEngine {
    ConversationResult process(ConversationRequest request);
}
```

Tipos de domínio propostos: `ConversationRequest` contém conversationId, turnId, snapshot fixado, entrada e contexto autorizado; `ConversationResult` contém saídas tipadas, novo contexto, estado e trace de execução. Eles não expõem classes do SDK IBM.

Implementações previstas: `IbmV1ConversationEngine`, `IbmV2ConversationEngine` e `LocalConversationEngine`. Criação e encerramento de sessões ficam em um contrato de ciclo de vida complementar. Selecionar engine ao abrir atendimento; trocar engine ou snapshot durante a conversa exige migração explícita ou nova conversa.

## Semântica mínima do runtime local

Definir uma linguagem interna versionada para condições e variáveis. Nunca usar `eval`, compilação de Java ou execução arbitrária do JSON. Antes de executar, compilar/validar o fluxo e recusar recursos sem implementação; não ignorá-los silenciosamente.

Primeira versão proposta: estado, variáveis tipadas, condições simples, respostas textuais, transições e encerramento. Em seguida, considerar subfluxos, chamadas externas e classificação. Compatibilidade integral com Dialogs e Actions é objetivo futuro, não garantia do MVP.

Aplicar limite de passos por turno, tempo total, profundidade de subfluxos, tamanho do contexto e chamadas externas. Registrar nós visitados e decisões de forma sanitizada. Entradas simultâneas da mesma conversa devem ser ordenadas e controladas por revisão.

## Critério de compatibilidade

Manter corpus de fluxos e conversas de referência, com fixtures sanitizadas. Comparar saídas, estado e transições entre IBM e runtime local, documentando divergências. Separar diferenças de interpretação determinística das diferenças de serviços probabilísticos de linguagem. A interface comum reduz acoplamento, mas não assegura equivalência de comportamento.
