# Escopo e requisitos

## Objetivo confirmado

Construir um backend Java para uma aplicação web de gestão de atendimentos, processamento de conversas, integração IBM, gerenciamento de portfólios e versionamento de fluxos JSON. Suportar APIs IBM v1 e v2, além de Dialogs e Actions. Usar MySQL e MongoDB. Preparar integração futura com React Flow e um runtime próprio.

O usuário informou que seu plano IBM permite as operações já testadas em outro projeto, incluindo testes, inclusão, alteração e exclusão de actions e diálogos. Essa informação orienta o escopo; endpoints, payloads e resultados desses testes ainda precisam ser incorporados à implementação.

## Requisitos funcionais

| ID | Requisito | Entrega |
|---|---|---|
| RF01 | Login com usuário e senha, logout e controle de acesso | Fundação |
| RF02 | Cadastro de usuários e associação a portfólios | Fundação |
| RF03 | Gestão de portfólios e projetos | Fundação |
| RF04 | Cadastro seguro de integrações IBM e ambientes | Gestão |
| RF05 | Importação e exportação de JSON de Dialogs e Actions | Gestão |
| RF06 | Rascunhos, validação, snapshots e comparação de versões | Gestão |
| RF07 | Inclusão, atualização e exclusão remotas conforme capacidade do recurso | Integração |
| RF08 | Publicação rastreável de uma versão em um destino | Integração |
| RF09 | Testes de conversa usando APIs v1 e v2 quando aplicáveis | Orquestração |
| RF10 | Abertura e encerramento de atendimentos, mensagens e histórico | Orquestração |
| RF11 | Diagrama com mapeamento entre elementos visuais e JSON de origem | Editor |
| RF12 | Execução local de um subconjunto documentado dos fluxos | Runtime futuro |

## Requisitos não funcionais propostos

- Autorizar acesso por portfólio em todas as consultas e operações.
- Manter versões publicadas imutáveis e registrar autoria das alterações.
- Preservar campos desconhecidos do JSON importado.
- Garantir rastreabilidade de operações remotas e recuperação de falhas parciais.
- Aplicar limites de payload, duração e tentativas de execução.
- Manter credenciais fora de respostas, logs e documentos de fluxo.
- Validar regras críticas com testes de integração e contratos.
- Definir SLOs de disponibilidade e latência após medir a carga e a dependência IBM.

## Limites iniciais

O frontend e a equivalência completa com o motor IBM não fazem parte da primeira entrega. Classificação de intenções, extração de entidades, busca e integrações externas exigem implementações específicas; interpretar o JSON não reproduz automaticamente esses serviços.

## Definições pendentes

- Significado de portfólio no negócio; proposta inicial: limite de organização e autorização que contém projetos.
- Atendimento inicialmente será a conversa e seu acompanhamento. Fila, transferência para atendente humano, SLA e canais precisam de requisitos próprios.
- Política de cadastro, recuperação de senha e eventual provedor corporativo de identidade.
- Ambientes IBM disponíveis, regiões, identificadores e contratos testados no projeto anterior.
- Volumes esperados, retenção, backup, hospedagem e orçamento de operação.
- Conjunto mínimo de recursos suportados pelo primeiro runtime local.
