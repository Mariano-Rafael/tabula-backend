# Integração IBM Watson / watsonx Assistant

Esta página registra o desenho geral. O estado implementado de configuração, destinos, autenticação e leitura está em [Integração IBM implementada](12-integracao-ibm-implementada.md), incluindo os resultados dos testes locais e as limitações por operação.

## Conceitos

Versão da API (`V1`, `V2`), tipo de conteúdo (`DIALOG`, `ACTION`), versão do fluxo no Tabula e release/ambiente IBM são dimensões diferentes. Um `workspaceId` não deve ser tratado como `assistantId` ou `skillId`.

A documentação oficial indica v1 para autoria de workspaces de Dialogs e v2 para execução com sessões. A execução v1 permanece relevante para compatibilidade e exige gerenciamento de estado pelo cliente. Isso não autoriza assumir que toda operação v1 possui equivalente v2, nem que Actions sejam um CRUD de dialog nodes.

Fontes: [visão geral das APIs](https://cloud.ibm.com/docs/watson-assistant?topic=watson-assistant-api-overview) e [referência v1](https://cloud.ibm.com/docs/apis/assistant-v1?code=java), consultadas em 2026-11-07.

## Matriz inicial de capacidades

| Conteúdo/operação | Encaminhamento | Evidência/status |
|---|---|---|
| Dialogs: workspaces, intents, entities, dialog nodes | Autoria v1 | Documentado na referência v1 |
| Dialogs: mensagem direta ao workspace | Runtime v1 | Compatibilidade; contexto controlado pelo Tabula |
| Execução do assistant e gerenciamento de sessões | Runtime v2 | Documentado na visão geral IBM |
| Actions: importação/exportação e alterações | Adaptador de autoria próprio | Usuário relata testes bem-sucedidos; incorporar contratos utilizados |
| Releases, ambientes e publicação | Operações específicas do recurso | Validar referência atual e instância alvo antes de implementar |
| Execução de Actions | Destino v2 compatível | Validar assistant, ambiente e payload no teste de contrato |

Referência geral: [API v2](https://cloud.ibm.com/docs/apis/assistant-v2?code=java). O acesso automatizado a essa página não foi concluído na documentação inicial. Posteriormente, os contratos de leitura foram conferidos no SDK oficial e testados por chamadas autenticadas; detalhes no documento 12.

O suporte a ambos é requisito confirmado. A matriz limita suposições de implementação, não reduz esse escopo. Não depender de endpoints privados capturados da interface web sem registrar explicitamente sua instabilidade e aprovar uma alternativa técnica.

## Contratos internos propostos

- `IbmDialogAuthoringGateway`: operações de autoria de Dialogs.
- `IbmActionAuthoringGateway`: operações de Actions conforme contratos verificados.
- `IbmV1RuntimeGateway`: mensagem ao workspace e contexto explícito.
- `IbmV2RuntimeGateway`: criação, uso e encerramento de sessões.
- `IntegrationCapabilityService`: capacidades efetivas por destino e operação.

Separar autenticação, transporte HTTP, paginação e tradução de erros dos modelos de domínio. Preferência inicial por cliente HTTP Spring; a adoção do SDK IBM depende da cobertura e compatibilidade verificadas. Fixar a data `version` exigida pela API em configuração validada, sem usar automaticamente a data do dia.

## Importação e publicação

Importar significa obter ou receber JSON, validar formato, registrar origem e gerar snapshot interno. Não significa publicar nem alterar a IBM.

Publicação proposta:

1. Autorizar ator e destino; verificar capacidade e versão `READY`.
2. Validar referências e gerar preview das alterações e exclusões.
3. Criar operação persistida ligada ao snapshot e destino exatos.
4. Executar a mudança; registrar identificador remoto e estado de processamento.
5. Acompanhar até confirmação, quando a API for assíncrona; só então marcar sucesso.
6. Quando possível, reler/exportar para verificar o resultado e detectar divergência.

Reversão é uma nova operação baseada em snapshot anterior, sujeita às capacidades remotas. Não existe rollback atômico envolvendo os bancos locais e a IBM. Edições externas precisam aparecer como divergência antes de sobrescritas.

## Falhas e limites

- Timeouts separados para conexão e resposta, limites de concorrência por integração e paginação completa.
- Retry com backoff e jitter apenas para falhas transitórias e operações que possam ser repetidas com segurança; respeitar `Retry-After` quando disponível.
- Não repetir automaticamente mensagem, criação ou publicação após timeout com efeito desconhecido. Marcar resultado `UNKNOWN` e reconciliar.
- Distinguir credencial inválida, permissão insuficiente, recurso ausente, rate limit, indisponibilidade e payload inválido.
- Registrar correlation ID e identificador de requisição remota quando fornecido, sem tokens ou payloads sensíveis.
- Expiração de sessão v2 deve produzir comportamento explícito; recriar sessão não garante preservação do contexto remoto anterior.

## Evidências necessárias do projeto anterior

Incorporar exemplos sanitizados de requests/responses de Actions e Dialogs, URLs públicas documentadas, método HTTP, data de versão, identificadores usados, resultado de treinamento/publicação e comportamento de exclusão. Transformar esses exemplos em fixtures e testes de contrato, sem copiar chaves ou dados pessoais.
