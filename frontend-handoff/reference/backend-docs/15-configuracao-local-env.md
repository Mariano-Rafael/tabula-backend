# Configuração local com .env

O `application.properties` importa `optional:file:./.env[.properties]`. O arquivo `.env` deve ficar na raiz do projeto, fora de `src/main/resources`. A execução pelo IntelliJ ou Maven deve usar essa raiz como working directory.

O carregamento é nativo do Spring Boot, sem biblioteca dotenv. Os valores entram no Environment do Spring; não são exportados para o sistema operacional. Variáveis reais do processo têm precedência sobre valores do arquivo.

Conexões IBM próprias por portfólio usam `IBM_CREDENTIAL_ENCRYPTION_KEY`, chave de 32 bytes em Base64 para proteger API keys no MySQL. Uma chave aleatória foi adicionada ao `.env` local, sem exibição. Preserve-a entre reinícios e no processo de backup/restauração; não a versione. Em produção, forneça-a por secret do deploy. Ela é diferente de `IBM_API_KEY`, usada apenas pelos vínculos legados PROPERTIES. Consulte [o contrato de instâncias por portfólio](20-instancias-ibm-por-portfolio.md).

## Preparar

Copiar [o exemplo](../../docs/integracao-backend.md#ambiente-e-conexao) para `.env` e preencher os dados locais. O arquivo real continua ignorado pelo Git e foi excluído explicitamente dos recursos Maven, inclusive se uma cópia for colocada acidentalmente em resources.

O conteúdo é interpretado como **Java Properties**, não como script shell:

- Usar `NOME=valor`, sem `export` e sem aspas delimitadoras.
- Não colocar comentários na mesma linha do valor.
- Barras invertidas literais precisam ser escapadas como `\\`.
- Não declarar autorreferências como `MYSQL_URL=${MYSQL_URL:}` no application.properties.

A migração do arquivo existente removeu somente aspas delimitadoras e escapou barras invertidas para preservar os valores lidos pelo Spring.

## Nomes e referências

| Nome no .env | Consumidor |
|---|---|
| IBM_API_KEY | `ibm.watson.api-key` |
| IBM_SERVICE_URL | Configuração do cliente IBM |
| IBM_ASSISTANT_ID, IBM_DIALOG_SKILL_ID, IBM_ACTION_SKILL_ID | Defaults de destinos |
| IBM_DRAFT_ENV_ID, IBM_LIVE_ENV_ID | Defaults de ambientes |
| IBM_DIALOG_WORKSPACE_ID | Workspace v1 opcional |
| MYSQL_URL, MYSQL_USERNAME, MYSQL_PASSWORD | Datasource do profile identity |
| MONGODB_URI, MONGODB_DATABASE | Cliente MongoDB e banco de documentos |
| SESSION_COOKIE_SECURE | Cookie HTTPS; false somente para HTTP local |

O nome antigo `API_KEY` foi padronizado para `IBM_API_KEY`. Campos opcionais podem ficar vazios; a chave IBM ausente é identificada ao tentar autenticar no provedor. Mongo URI é obrigatória para os módulos documentais. O bootstrap administrativo permanece desabilitado conforme a configuração atual.

O `.gitignore` mantém o `.env` privado e permite versionar especificamente `src/main/resources/application.properties`, que agora contém apenas referências e opções não sensíveis, além de `.env.example`.

O profile `identity` continua configurando MySQL. Não duplicar os valores das credenciais em application.properties: manter somente referências e opções não sensíveis.

## Validação

O teste local com MySQL/MongoDB e gateway IBM simulado passou após a migração, confirmando o carregamento do .env e a autenticação nos bancos, sem imprimir credenciais. O teste remove seus próprios registros temporários.

A configuração Maven exclui `**/.env` e `**/.env.*` do JAR. Em produção, usar o gerenciador de segredos/variáveis da infraestrutura; o arquivo local não acompanha o artefato.

O build do pacote também foi validado: o JAR contém application.properties e nenhuma entrada .env.

Referência: [Configuração externa do Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html).
