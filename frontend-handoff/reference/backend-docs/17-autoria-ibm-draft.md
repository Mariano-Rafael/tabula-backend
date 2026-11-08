# Atualização de skills IBM no Draft

Atualização: 08/11/2026.

## Escopo implementado

Aplicação do `workspace` de uma versão local imutável `READY` a uma skill existente de Dialog ou Action, pelo endpoint V2 `POST /v2/assistants/{assistant_id}/skills/{skill_id}`. Dialog clássico associado ao Assistant não precisa de workspaceId. O corpo remoto contém somente `workspace`: não altera nome/descrição da skill, skills irmãs, configurações do Assistant ou ambiente Live.

Uma versão pode conter inclusão, alteração e remoção de nós/actions no JSON completo. Isso não equivale a oferecer endpoints granulares desses elementos ou criar/remover a própria skill. Criação/exclusão de skills, releases, publicação Live, rollback automático e autorias diretas V1 continuam fora desta entrega.

As datas e contratos foram conferidos no [SDK oficial IBM](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v2/Assistant.java), no modelo [UpdateSkillOptions](https://github.com/watson-developer-cloud/java-sdk/blob/master/assistant/src/main/java/com/ibm/watson/assistant/v2/model/UpdateSkillOptions.java) e na [referência V2](https://cloud.ibm.com/docs/apis/assistant-v2?code=java). A documentação associa Update skill a planos Enterprise. Na instância de teste configurada, a atualização real de Dialog e Action foi validada em 08/11/2026; essa evidência não garante disponibilidade em outros planos/destinos.

## Rotas

Todas usam sessão autenticada. POST exige CSRF. OWNER, EDITOR e administrador global podem preparar/aplicar/reconciliar e baixar backup; OPERATOR e VIEWER só consultam metadados e listagem.

| Método | Rota `/api/v1` | Entrada/resultado |
|---|---|---|
| POST | `/projects/{id}/ibm-writes` | `{targetId, versionId}`, `Idempotency-Key` de 8–100 caracteres; prepara sem mutação IBM |
| GET | `/projects/{id}/ibm-writes?page=0&size=20` | Histórico paginado de operações, size 1–100 |
| GET | `/ibm-writes/{id}` | Estado, hashes, versão, destino, autor e código sanitizado |
| GET | `/ibm-writes/{id}/backup` | JSON bruto do workspace anterior à atualização |
| POST | `/ibm-writes/{id}/apply` | Sem corpo; inicia atualização real uma única vez |
| POST | `/ibm-writes/{id}/reconcile` | Sem corpo; consulta IBM e verifica o conteúdo, sem reenviar |

Preparação READY responde 201. Operações pendentes respondem 202, SUCCEEDED responde 200, CONFLICT responde 409 e FAILED responde 422. GET de metadados responde 200 independentemente do estado; sempre inspecione `state` e `errorCode`. Falhas de validação/autorização e de serviços seguem ProblemDetail. Uma exportação inicial pendente pode responder 202 `IBM_EXPORT_PENDING` antes de existir uma operação; repita a mesma preparação com chave/corpo iguais.

## Persistência e sequência

1. Autoriza projeto/destino ativo DRAFT e versão do mesmo projeto, exige skill correspondente configurada e fluxo não arquivado.
2. Lê a versão imutável com verificação de checksum e exporta exatamente a skill configurada. Dados de outras skills não entram no payload.
3. Cria metadados PREPARING no MySQL, incluindo binding congelado, autor, hashes, chave de idempotência e lease de dois minutos. Insere backup/candidato em `ibm_skill_write_documents` no MongoDB como strings JSON. Somente depois confirma READY.
4. Apply verifica escopo e integridade dos documentos e adquire bloqueio persistido por endpoint/assistant/skill, compartilhado entre projetos e destinos. Faz nova leitura para comparar com o backup; divergência gera CONFLICT/`IBM_REMOTE_CHANGED` sem enviar alteração.
5. Persiste SENDING antes da única chamada POST IBM. Resposta confirmada inicia PROCESSING; isso ainda não é sucesso. Timeout, resposta inválida ou falha incerta geram UNKNOWN. Rejeição explícita reconhecida gera FAILED.
6. Reconcile consulta Get skill e espera status Available. Reexporta a skill e compara o hash do workspace com o candidato. Igualdade confirma SUCCEEDED; conteúdo diferente mantém UNKNOWN/`IBM_WRITE_CONTENT_NOT_CONFIRMED`. Status remoto Failed gera FAILED.

A migration V6 cria `ibm_skill_writes` e `ibm_skill_write_locks`. Não há transação SQL aberta durante HTTP IBM nem durante gravação Mongo. O Mongo é necessário antes de qualquer envio. O documento armazena apenas workspace anterior/candidato: não é um backup integral do Assistant.

As tabelas de operações mantêm IDs históricos sem FKs de exclusão em cascata. Os dados de operação e backup não têm política de expurgo nesta etapa; devem ser incluídos na definição futura de retenção. O binding fica no servidor e não é serializado nas respostas de metadados.

## Falhas, recuperação e limites

- Repetir preparação com a mesma chave/corpo retorna a mesma operação; chave reutilizada com outra versão/destino gera 409. Corrida entre preparações simultâneas pode retornar 409 e ser consultada repetindo a requisição original.
- Repetir apply depois do despacho não envia novo POST, inclusive em UNKNOWN/PROCESSING. Para uma nova edição, crie uma nova versão/operação, depois de resolver a anterior.
- PREPARING/CHECKING abandonados podem virar FAILED por reconcile após o lease, pois ainda não despacharam. SENDING vencido vira UNKNOWN; não é reenviado.
- Exportação assíncrona pendente, rate limit e falhas transitórias reconhecidas de leitura durante o preflight devolvem a operação a READY, registram o código e liberam seu bloqueio, sem envio. Repita apply posteriormente na mesma operação, após aguardar. Nenhuma nova tentativa automática de mutação é feita. O código anterior é limpo ao confirmar SENDING.
- UNKNOWN e PROCESSING mantêm o bloqueio da skill. SUCCEEDED, CONFLICT e FAILED liberam. Não há expiração automática desse bloqueio nem endpoint de desbloqueio forçado. Se o conteúdo não puder ser confirmado, é necessária investigação operacional; criar outra chave não contorna a proteção.
- Reconcile usa binding congelado e continua disponível após arquivamento/desativação a editores autorizados. Mudança do endpoint global bloqueia o uso do binding antigo. Mudança do cadastro de destino bloqueia apply, mas não redireciona a reconciliação.
- O preflight detecta divergências anteriores à leitura. O contrato IBM utilizado não fornece compare-and-swap/If-Match: uma edição externa entre a leitura e o POST ainda pode ser sobrescrita. Evite editar a mesma skill simultaneamente fora do Tabula.
- A comparação de conteúdo é estrita, com ordem de propriedades de objetos normalizada e ordem de arrays preservada. Normalizações de conteúdo feitas pela IBM podem impedir confirmação e manter UNKNOWN. O teste real deve verificar a forma efetivamente aceita/devolvida pelo provedor antes de uso operacional.
- O limite local do JSON é 4 MiB e profundidade 64. Não há retry automático de mutação, publicação, restauração automática ou processamento agendado. O backup pode ser baixado e usado na preparação de uma nova versão restauradora, após resolver o estado da operação anterior.

## Validação

`SkillWriteModuleTests`: 16 testes com H2, HTTP de aplicação e provedor/documentos simulados, cobrindo idempotência, durabilidade anterior ao envio, alterações externas, bloqueio por recurso, integridade, timeout, estados vencidos, permissões/CSRF, alvos Live, arquivamento, rate limit antes do envio e Dialog/Action sem workspaceId.

`IbmSkillWriteGatewayTests`: 2 testes com MockRestServiceServer, verificando POST V2 exato, corpo somente workspace, autenticação, consulta de status, invalidação de token e ausência de retry.

`LocalFlowImportTests` opt-in foi ampliado e passou com IBM GET real, MySQL e MongoDB reais para DIALOG e ACTION: importação, versão, preparação READY, backup com hash válido e replay. A migration V6 foi aplicada no MySQL local. Somente fixtures próprias foram removidas; nenhuma skill remota foi atualizada. Log sanitizado: `target/ibm-write-real-preparation.log`.

O teste real de `apply`/Update skill foi realizado após autorização explícita para modificar os IDs IBM de teste, conforme registro abaixo. A collection Bruno contém a pasta `07-autoria-ibm-manual` para revisar backup/versão e executar cada etapa explicitamente.

A suíte completa após o ajuste de rate limit passou: 105 testes executados com zero falhas/erros e 11 casos opt-in pulados por padrão (116 registrados no total). Log: `target/ibm-write-post-live-suite.log`. Os 70 arquivos Bruno (68 requisições, collection e ambiente exemplo) e seus scripts passaram na validação sintática com o parser oficial na entrega da collection. Isso não representa execução das 68 requisições contra um servidor real.

## Teste real de atualização e restauração

`RealIbmSkillWriteTests` é opt-in e **altera as skills Draft configuradas**. Deve ser executado somente com IDs de teste autorizados. Para cada tipo, importa o original, muda apenas o título do primeiro nó/action existente, cria uma versão local, aplica a atualização, confere o resultado e aplica novamente o original. A comparação final exige o hash do workspace original. Não envia mensagens nem publica em Live.

```powershell
./mvnw.cmd '-Dtest=RealIbmSkillWriteTests' '-Dtabula.skill.write.verify=true' test
```

As skills precisam ter pelo menos um nó/action. Se a atualização ou a restauração ficar incerta, o teste preserva versões, backups, metadados e bloqueios locais e registra o ID local da operação para investigação. Não repita indiscriminadamente esse teste após falha: consulte e reconcilie a operação preservada. Somente fixtures próprias com estado remoto seguro são removidas. Logs não incluem JSON exportado nem credenciais.

### Resultado real — 08/11/2026

- ACTION: título do primeiro elemento alterado; atualização SUCCEEDED após leitura de confirmação; repetição de apply não reenviou; restauração do original SUCCEEDED e hash original confirmado. Fixtures locais removidas.
- DIALOG: atualização enviada; a primeira execução foi interrompida por `IBM_RATE_LIMITED` em leitura de confirmação. O backup e a operação PROCESSING foram preservados. A recuperação consultou a mesma operação, aguardou o rate limit, confirmou SUCCEEDED e aplicou uma operação restauradora com a versão original. Hash do workspace original confirmado e fixtures locais removidas. A alteração inicial não foi reenviada.

O primeiro comando teve uma falha de leitura no caso Dialog; portanto, não é registrado como uma suíte inteiramente aprovada. O caso Action passou nessa execução e o fluxo de confirmação/restauração Dialog passou em execução separada. Logs sanitizados: `target/ibm-write-real.log`, `target/ibm-write-recovery.log` e `target/ibm-write-restore.log`.

A recuperação opt-in é executada com seleção explícita do método, `-Dtabula.skill.write.recover=true` e `-Dtabula.skill.write.recovery-id=<UUID local da operação>`. `inspectPreservedOperations` apenas inspeciona/reconcilia fixtures de autoria; `resumeAndRestorePreservedOperation` confirma a operação, restaura a versão IBM original e limpa suas fixtures. Ambos exigem também a flag principal `tabula.skill.write.verify=true`. IDs removidos após limpeza não são reutilizáveis.

Essa validação demonstra atualização/restauração do campo de título de elementos existentes em ambas as skills, com persistência real e verificação de conteúdo. Não demonstra todas as alterações estruturais possíveis, criação/exclusão de skills, publicação Live ou execução de todas as rotas HTTP; o teste aciona os serviços internos.

### Exclusão e recriação de nó Dialog

O caso opt-in `RealIbmSkillWriteTests#dialogNodeDeleteAndRecreate` testa o ciclo estrutural de um nó temporário dentro da skill Dialog Draft existente, pela atualização de workspace V2. Não exclui/recria a skill inteira e não depende de workspaceId ou dos endpoints de autoria V1.

```powershell
./mvnw.cmd '-Dtest=RealIbmSkillWriteTests#dialogNodeDeleteAndRecreate' '-Dtabula.skill.write.verify=true' test
```

O teste importa uma cópia do original, cria um nó de ID único baseado em um nó standard existente e o acrescenta ao final da cadeia de irmãos da raiz. O nó usa condição `false`, fica fora de sugestões de desambiguação e não contém ações/contexto do modelo copiado. Nenhuma mensagem é enviada. Cada etapa usa uma versão imutável e uma nova operação persistida:

1. Aplica o workspace com o nó temporário e verifica sua presença única na exportação IBM.
2. Aplica o original e verifica a ausência do nó e a igualdade com o hash original.
3. Reaplica a versão com o nó temporário, preservando exatamente seu ID e conteúdo, e verifica a igualdade do nó exportado.
4. Restaura o workspace original e só então remove suas fixtures locais.

Os mesmos controles de backup, idempotência, leitura de confirmação, rate limit e preservação de estado incerto são usados. O teste para antes de qualquer mutação se a cadeia de irmãos raiz não tiver uma única cauda ou se não houver um nó standard raiz para servir de modelo.

Resultado real em 08/11/2026: criação confirmada, exclusão com ausência confirmada na exportação, recriação com o mesmo identificador e conteúdo confirmada e workspace original restaurado com igualdade de hash. As fixtures locais foram removidas. Houve rate limit na leitura de confirmação; o teste aguardou e repetiu somente as leituras. Log sanitizado: `target/ibm-dialog-delete-recreate.log`. Essa execução valida exclusão/recriação de **nó de diálogo**, via atualização V2 da skill existente; não valida exclusão/recriação da skill inteira.
