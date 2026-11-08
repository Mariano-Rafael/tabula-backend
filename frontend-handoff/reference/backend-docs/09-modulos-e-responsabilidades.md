# Módulos e responsabilidades

## Decisão

Estado atual: `conversation` delega o processamento exclusivamente a `integration`, com modos IBM V1/V2, e `history` fornece consultas. O módulo `runtime` e o motor local abaixo são desenho futuro, não implementação existente. Consulte [Atendimentos IBM e histórico](14-atendimentos-ibm-e-historico.md).

Manter uma aplicação Spring MVC com módulos por funcionalidade. Separar desde o início acesso, gestão, processamento e consulta de conversas. Esta documentação define limites para a implementação; não cria funcionalidades ou pacotes vazios.

## Mapa de responsabilidades

| Módulo | Responsabilidade | Fora da responsabilidade |
|---|---|---|
| `identity` | Login, logout, usuários, sessão e identidade autenticada | Processamento de conversas |
| `portfolio` | Portfólios, membros, papéis, projetos e decisão de acesso ao portfólio | Autenticação e credenciais IBM |
| `integration` | Configuração de destinos, segredos e clientes IBM de autoria e runtime | Regras do atendimento ou interpretação local |
| `flow` | Cadastro, JSON original, rascunhos, validações e snapshots | Contexto de uma conversa ativa |
| `conversation` | Abrir/encerrar atendimento, fixar versão e engine, ordenar e registrar turnos | Implementar as regras do motor |
| `runtime` | Contrato das engines, processamento de entrada, contexto e trace de execução | Listagens e telas de histórico |
| `history` | Consultas autorizadas, transcrições, filtros e leitura de traces | Enviar mensagens ou reexecutar conversas |
| `diagram` | Projeção do fluxo, mapeamento para origem e layout visual | Renderizar React ou executar o fluxo |
| `operation` | Trabalhos persistidos, outbox, tentativas e reconciliação | Decidir regras de publicação ou acesso |
| `audit` | Registro de ações administrativas e seus resultados | Substituir transcrições ou traces do motor |
| `shared` | Tipos técnicos mínimos, erros e correlação | Concentrar serviços de todos os módulos |

## Camadas MVC por módulo

```text
conversation/
  controller/   endpoints HTTP de abertura, mensagem e encerramento
  service/      casos de uso e coordenação
  repository/   persistência de atendimentos e turnos
  model/        estado e regras do atendimento
  dto/          entrada e saída HTTP

runtime/
  service/      contrato de processamento e seleção de engine
  model/        entrada, resultado, contexto e eventos de execução
  engine/       implementação local e adaptadores de execução IBM

history/
  controller/   endpoints de consulta
  service/      autorização, filtros e composição de resultados
  dto/          resumos, transcrições e traces para consulta
```

Nem todo módulo precisa de controller ou repository. O runtime é chamado inicialmente pelo serviço de atendimento; não haverá endpoint público que permita contornar a autorização do atendimento. Classes dos clientes IBM ficam em `integration/client`, com divisões v1/v2 conforme a necessidade.

## Acesso

`identity` fornece a identidade autenticada. `portfolio` mantém associações e decide permissões no portfólio. Os serviços de cada funcionalidade aplicam essas permissões ao recurso antes de agir. Isso evita que um módulo de acesso precise conhecer fluxos, atendimentos e clientes IBM.

O frontend poderá usar permissões para organizar menus, mas esconder uma tela não substitui a validação no backend. Leitura de transcrições, leitura de traces, processamento e publicação devem admitir permissões distintas, mesmo quando inicialmente atribuídas ao mesmo papel.

## Atendimento e processamento

1. O controller recebe a mensagem e a identidade autenticada.
2. `conversation` verifica o acesso, o estado do atendimento, a chave de idempotência e a ordenação do turno.
3. Resolve a versão imutável e a engine fixadas na abertura e solicita processamento a `runtime`.
4. O runtime usa sua implementação local ou chama o gateway de `integration` correspondente à API IBM.
5. O processamento produz saída, contexto proposto e trace. `conversation` coordena a confirmação do turno e do contexto por contratos de serviço e pelo protocolo de consistência documentado.
6. `history` consulta os registros confirmados ou apresenta explicitamente o estado pendente/incerto.

`conversation` é dono do ciclo de vida e do registro do turno; `runtime` é dono das regras e da representação do contexto de execução. A persistência física segue [Modelo de dados](03-modelo-de-dados.md). Essa divisão não implica transação atômica entre os bancos ou execução exatamente uma vez na IBM.

## Visualização e histórico

Existem duas visualizações diferentes:

- **Diagrama do fluxo:** `diagram` projeta a estrutura de uma versão para o futuro React Flow.
- **Conversa e percurso executado:** `history` apresenta mensagens, estados e traces, podendo associar eventos aos IDs dos nós do diagrama da versão usada.

`history` começa como uma camada de consulta sobre contratos de leitura de `conversation` e `runtime`, sem copiar todas as mensagens para uma nova base. Se consultas exigirem projeções próprias no futuro, elas terão atualização idempotente e indicarão eventual atraso.

As rotas de histórico podem continuar sob `/conversations/{id}`: URL e nome do módulo Java não precisam ser iguais. Controllers de consulta delegam ao serviço de histórico; controllers de comando delegam ao serviço de atendimento.

Uma transcrição é o conteúdo trocado na conversa. Um trace explica passos do processamento. Uma auditoria registra quem realizou uma ação no sistema. Esses registros têm permissões, retenção e finalidade próprias.

## Dependências permitidas

- `conversation` usa contratos de `runtime`, `flow` e autorização de `portfolio`.
- `runtime` usa gateways de `integration`; não consulta `history`.
- `flow` usa autoria de `integration` e coordena publicação com `operation`.
- `diagram` usa contratos de leitura de `flow`; alterações semânticas passam pelos serviços de `flow`.
- `history` usa contratos de leitura; não chama operações que alteram atendimento ou engine.
- `operation` fornece infraestrutura de execução de trabalhos. Handlers dos domínios mantêm as regras de cada operação, evitando dependências circulares.
- Os módulos registram auditoria pelo contrato de `audit`, sem expor segredos.

Não compartilhar repositories entre módulos nem duplicar entidades para facilitar imports. Usar DTOs internos de serviço ou interfaces pequenas quando uma funcionalidade precisar de informação de outra. Testar os limites quando as primeiras dependências reais forem implementadas.

## Sequência de implementação

Criar cada módulo junto de seus primeiros casos de uso. Começar pelos contratos e clientes de `integration`, já priorizados, mantendo identidade e autorização como requisito antes de expor operações administrativas ou transcrições a usuários. Implementar processamento em `runtime` separado da coordenação em `conversation`; adicionar consultas em `history` conforme os registros forem persistidos.
