// Run with node scripts/generate-bruno.cjs. No credentials are read by this generator.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../bruno');
fs.mkdirSync(root, {recursive:true});
fs.writeFileSync(path.join(root,'bruno.json'), JSON.stringify({version:'1',name:'Tabula Backend',type:'collection',ignore:['node_modules','.git']},null,2)+'\n');
fs.writeFileSync(path.join(root,'collection.bru'), 'meta {\n  name: Tabula Backend\n}\n\nscript:pre-request {\n  if (!["GET", "HEAD", "OPTIONS"].includes(req.getMethod())) {\n    const token = bru.getVar("csrfToken");\n    if (token) req.setHeader(bru.getVar("csrfHeader") || "X-CSRF-TOKEN", token);\n  }\n}\n');
fs.mkdirSync(path.join(root,'environments'),{recursive:true});
fs.writeFileSync(path.join(root,'environments/Local.example.bru'), `vars {
  baseUrl: http://localhost:8080
  email: admin@example.invalid
  password:
  testUserEmail: test@example.invalid
  testUserPassword:
  newPassword:
  message: DEFINA_UMA_MENSAGEM_SEGURA
  mode: V2
  openKey: open-CHANGE-ME-001
  messageKey: message-CHANGE-ME-001
  uploadKey: upload-CHANGE-ME-001
  importDialogKey: import-dialog-CHANGE-ME-001
  importActionKey: import-action-CHANGE-ME-001
  snapshotKey: snapshot-CHANGE-ME-001
  writeKey: write-CHANGE-ME-001
  ibmServiceUrl: https://api.us-south.assistant.watson.cloud.ibm.com/instances/REPLACE_ME
  ibmApiKey:
  ibmNextApiKey:
  ibmAssistantId: REPLACE_ME
  ibmEnvironmentId: REPLACE_ME
  ibmDialogSkillId:
  ibmActionSkillId:
  dialogNodeId: REPLACE_WITH_IBM_NODE_ID
}
`);
let seq=0; const inventory=[];
function add(folder,name,method,url,body=null,options={}) {
  seq++; const dir=path.join(root,folder); fs.mkdirSync(dir,{recursive:true});
  const headers={...options.headers}; if(body!==null) headers['Content-Type']='application/json';
  let out=`meta {\n  name: ${name}\n  type: http\n  seq: ${seq}\n}\n\n${method.toLowerCase()} {\n  url: {{baseUrl}}/api/v1${url}\n  body: ${body===null?'none':'json'}\n  auth: none\n}\n`;
  if(Object.keys(headers).length) out+='\nheaders {\n'+Object.entries(headers).map(([k,v])=>`  ${k}: ${v}`).join('\n')+'\n}\n';
  if(body!==null) out+='\nbody:json {\n'+(typeof body==='string'?body:JSON.stringify(body,null,2)).split('\n').map(l=>'  '+l).join('\n')+'\n}\n';
  let post=options.post||'';
  if(options.capture) post+=`\nif (res.getStatus() < 300 && res.getBody().id) bru.setVar("${options.capture}", res.getBody().id);`;
  if(post) out+='\nscript:post-response {\n'+post.split('\n').map(l=>'  '+l).join('\n')+'\n}\n';
  out+=`\ntests {\n  test("Status esperado", function () {\n    expect(${JSON.stringify(options.status||[200])}).to.include(res.getStatus());\n  });\n}\n`;
  out+='\ndocs {\n  '+(options.doc||'Execute individualmente, seguindo o README da collection.').replace(/\n/g,'\n  ')+'\n}\n';
  fs.writeFileSync(path.join(dir,name+'.bru'),out); inventory.push([folder,name,method,url]);
}
const csrf={post:'const b = res.getBody();\nif (res.getStatus() === 200) { bru.setVar("csrfToken", b.token); bru.setVar("csrfHeader", b.headerName); }'};
add('01-auth','01-CSRF','GET','/auth/csrf',null,csrf);
add('01-auth','02-Login','POST','/auth/login',{email:'{{email}}',password:'{{password}}'});
add('01-auth','03-Renovar-CSRF','GET','/auth/csrf',null,csrf);
add('01-auth','04-Minha-conta','GET','/auth/me');
add('02-portfolios','01-Criar','POST','/portfolios',{name:'Portfolio Bruno',description:'Testes manuais'},{capture:'portfolioId',status:[201]});
add('02-portfolios','02-Listar','GET','/portfolios?page=0&size=20&includeArchived=false');
add('02-portfolios','03-Consultar','GET','/portfolios/{{portfolioId}}');
add('02-portfolios','04-Atualizar','PATCH','/portfolios/{{portfolioId}}',{name:'Portfolio Bruno atualizado',description:'Testes manuais'});
add('02-portfolios','05-Membros','GET','/portfolios/{{portfolioId}}/members?page=0&size=20');
add('02-portfolios','06-Adicionar-membro','POST','/portfolios/{{portfolioId}}/members',{userId:'{{userId}}',role:'VIEWER'},{status:[201]});
add('02-portfolios','07-Alterar-papel','PATCH','/portfolios/{{portfolioId}}/members/{{userId}}',{role:'OPERATOR'});
add('03-projetos','01-Criar','POST','/portfolios/{{portfolioId}}/projects',{name:'Projeto Bruno',description:'Teste IBM'},{capture:'projectId',status:[201]});
add('03-projetos','02-Listar','GET','/portfolios/{{portfolioId}}/projects?page=0&size=20&includeArchived=false');
add('03-projetos','03-Consultar','GET','/projects/{{projectId}}');
add('03-projetos','04-Atualizar','PATCH','/projects/{{projectId}}',{name:'Projeto Bruno atualizado',description:'Teste IBM'});
add('04-ibm','01-Criar-integracao','POST','/portfolios/{{portfolioId}}/integrations',{name:'IBM properties'},{capture:'integrationId',status:[201]});
add('04-ibm','01-Criar-integracao-isolada','POST','/portfolios/{{portfolioId}}/integrations',{name:'Instancia IBM do portfolio',serviceUrl:'{{ibmServiceUrl}}',apiKey:'{{ibmApiKey}}'},{capture:'integrationId',status:[201],doc:'Alternativa ao vinculo legado properties. OWNER pode cadastrar endpoint e API key proprios. Uma integracao por portfolio. A API key e criptografada no servidor e nao e devolvida. Configure as variaveis somente no ambiente local ignorado pelo Git.'});
add('04-ibm','02-Listar-integracoes','GET','/portfolios/{{portfolioId}}/integrations');
add('04-ibm','03-Consultar-integracao','GET','/integrations/{{integrationId}}');
add('04-ibm','04-Atualizar-integracao','PATCH','/integrations/{{integrationId}}',{name:'IBM propriedades',active:true});
add('04-ibm','05-Defaults-Draft','GET','/integrations/{{integrationId}}/defaults?environmentType=DRAFT',null,{post:'if (res.getStatus() === 200) bru.setVar("targetConfiguration", JSON.stringify(res.getBody()));'});
add('04-ibm','06-Testar-integracao','POST','/integrations/{{integrationId}}/connection-tests');
add('04-ibm','07-Criar-destino','POST','/projects/{{projectId}}/targets','{ "integrationId": "{{integrationId}}", "configuration": {{targetConfiguration}} }',{capture:'targetId',status:[201]});
add('04-ibm','07-Criar-destino-isolado','POST','/projects/{{projectId}}/targets',{integrationId:'{{integrationId}}',configuration:{name:'Draft da instancia',environmentType:'DRAFT',assistantId:'{{ibmAssistantId}}',environmentId:'{{ibmEnvironmentId}}',dialogWorkspaceId:null,dialogSkillId:'{{ibmDialogSkillId}}',actionSkillId:'{{ibmActionSkillId}}',v1Version:'2021-11-27',v2Version:'2024-08-25'}},{capture:'targetId',status:[201],doc:'Alternativa ao destino baseado nos defaults globais. Informe IDs da instancia cadastrada no portfolio. Nao copia IDs do properties.'});
add('04-ibm','08-Listar-destinos','GET','/projects/{{projectId}}/targets');
add('04-ibm','09-Consultar-destino','GET','/targets/{{targetId}}');
add('04-ibm','10-Atualizar-destino','PUT','/targets/{{targetId}}','{{targetConfiguration}}');
add('04-ibm','11-Capacidades','GET','/targets/{{targetId}}/capabilities');
add('04-ibm','12-Testar-destino','POST','/targets/{{targetId}}/connection-tests');
add('04-ibm','13-Rotacionar-credencial','PUT','/integrations/{{integrationId}}/credentials',{apiKey:'{{ibmNextApiKey}}'},{doc:'Somente OWNER/admin, com CSRF. Substitui a API key de uma integracao DATABASE; endpoint nao muda. Invalidacao do cache ocorre na proxima chamada. Nao modifica a API key configurada no properties.'});
const version={status:[201,202],post:'if (res.getStatus() < 300 && res.getBody().id) { bru.setVar("versionId", res.getBody().id); bru.setVar("flowId", res.getBody().flowId); }'};
for(const type of ['DIALOG','ACTION']) add('05-fluxos','01-Importar-'+type,'POST','/projects/{{projectId}}/imports',{targetId:'{{targetId}}',type,name:'Importacao '+type},{...version,headers:{'Idempotency-Key':type==='DIALOG'?'{{importDialogKey}}':'{{importActionKey}}'},doc:'Pode retornar 202 IBM_EXPORT_PENDING. Repita com a mesma chave e corpo. Atualiza flowId/versionId; trabalhe em um fluxo por vez.'});
add('05-fluxos','02-Upload','POST','/projects/{{projectId}}/flows',{name:'Dialog local',type:'DIALOG',json:JSON.stringify({type:'dialog',workspace:{dialog_nodes:[]}})},{...version,headers:{'Idempotency-Key':'{{uploadKey}}'}});
add('05-fluxos','03-Listar','GET','/projects/{{projectId}}/flows?page=0&size=20&includeArchived=false');
add('05-fluxos','04-Consultar','GET','/flows/{{flowId}}');
add('05-fluxos','05-Renomear','PATCH','/flows/{{flowId}}',{name:'Fluxo Bruno'});
add('05-fluxos','06-Versoes','GET','/flows/{{flowId}}/versions?page=0&size=20');
add('05-fluxos','07-Consultar-versao','GET','/versions/{{versionId}}');
add('05-fluxos','08-Exportar','GET','/versions/{{versionId}}/export');
add('05-fluxos','09-Reconciliar','POST','/versions/{{versionId}}/reconcile');
const etag={post:'if (res.getStatus() === 200) bru.setVar("draftEtag", res.getHeader("etag"));'};
add('05-fluxos','10-Iniciar-rascunho','POST','/flows/{{flowId}}/draft',{versionId:'{{versionId}}'},etag);
add('05-fluxos','11-Ler-rascunho','GET','/flows/{{flowId}}/draft',null,etag);
add('05-fluxos','12-Salvar-rascunho','PUT','/flows/{{flowId}}/draft',{type:'dialog',workspace:{dialog_nodes:[]}},{...etag,headers:{'If-Match':'{{draftEtag}}'},doc:'Exemplo DIALOG vazio: substitua pelo JSON completo antes de enviar. If-Match preserva as aspas do ETag.'});
add('05-fluxos','13-Snapshot','POST','/flows/{{flowId}}/versions',null,{...version,headers:{'If-Match':'{{draftEtag}}','Idempotency-Key':'{{snapshotKey}}'}});
const layoutBody = {schemaVersion:1,nodes:[{id:'node_1',position:{x:100,y:200},collapsed:false}],viewport:{x:0,y:0,zoom:1}};
const layoutEtag = {post:'if (res.getStatus() < 300) bru.setVar("layoutEtag", res.getHeader("etag"));'};
add('05-fluxos','14-Criar-layout','PUT','/flows/{{flowId}}/layout',layoutBody,{...layoutEtag,status:[201],headers:{'If-None-Match':'*'},doc:'Cria somente metadados visuais. Substitua node_1 pelo ID visual escolhido. Nao altera o JSON IBM nem cria elementos de dialogo. 412 se layout ja existir: consulte e use If-Match.'});
add('05-fluxos','15-Ler-layout','GET','/flows/{{flowId}}/layout',null,layoutEtag);
add('05-fluxos','16-Salvar-layout','PUT','/flows/{{flowId}}/layout',layoutBody,{...layoutEtag,headers:{'If-Match':'{{layoutEtag}}'},doc:'Substitui integralmente o layout atual. ETag layout-N e independente do draftEtag. Nao envie data/edges ou o objeto completo do React Flow.'});
add('05-fluxos','17-Diagrama-rascunho','GET','/flows/{{flowId}}/diagram',null,{doc:'Requer rascunho existente. Projecao estrutural somente leitura, combinada com o layout atual. Use nodes[].id para salvar posicoes. Inspecione diagnostics; Actions ainda nao projetam passos internos.'});
add('05-fluxos','18-Diagrama-versao','GET','/versions/{{versionId}}/diagram',null,{doc:'Requer versao READY. Usa JSON imutavel e layout atual do fluxo; nao e um snapshot historico do layout. Nenhuma chamada IBM e feita.'});
add('05-fluxos','19-Editar-no-Dialog','PATCH','/flows/{{flowId}}/draft/dialog-nodes',{nodeId:'{{dialogNodeId}}',title:'Titulo atualizado',conditions:'#intent',textEdits:[{responseIndex:0,valueIndex:0,text:'Resposta atualizada'}]},{...etag,headers:{'If-Match':'{{draftEtag}}'},doc:'Edicao local de no standard existente. Use data.sourceId do diagrama como dialogNodeId, e nao nodes[].id. Omita campos que nao deseja alterar. textEdits exige resposta text com values[].text literal nos indices indicados; nao cria respostas. Usa ETag do rascunho. Retorna JSON completo atualizado, sem aplicar na IBM.'});
add('06-conversas','01-Abrir','POST','/projects/{{projectId}}/conversations',{targetId:'{{targetId}}',mode:'{{mode}}'},{capture:'conversationId',status:[200,201,202],headers:{'Idempotency-Key':'{{openKey}}'}});
add('06-conversas','02-Listar','GET','/projects/{{projectId}}/conversations?page=0&size=20');
add('06-conversas','03-Consultar','GET','/conversations/{{conversationId}}');
add('06-conversas','04-Enviar-mensagem','POST','/conversations/{{conversationId}}/messages',{text:'{{message}}'},{capture:'turnId',status:[200,202,422],headers:{'Idempotency-Key':'{{messageKey}}'},doc:'Chamada real IBM. Defina uma mensagem segura. Reenvio identico usa a mesma chave; nova mensagem exige nova chave. 202 e 422 exigem inspecao do estado.'});
add('06-conversas','05-Historico','GET','/conversations/{{conversationId}}/messages?page=0&size=20');
add('06-conversas','06-Consultar-turno','GET','/conversations/{{conversationId}}/messages/{{turnId}}');
add('06-conversas','07-Reconciliar-turno','POST','/conversations/{{conversationId}}/messages/{{turnId}}/reconcile',null,{status:[200,202,422]});
add('06-conversas','08-Reconciliar-conversa','POST','/conversations/{{conversationId}}/reconcile');
add('06-conversas','09-Encerrar','POST','/conversations/{{conversationId}}/close',null,{status:[200,202]});
add('07-autoria-ibm-manual','01-Preparar-Draft','POST','/projects/{{projectId}}/ibm-writes',{targetId:'{{targetId}}',versionId:'{{versionId}}'},{capture:'writeId',status:[201,202],headers:{'Idempotency-Key':'{{writeKey}}'},doc:'Prepara atualizacao da skill existente no Draft; nao envia mutacao. Usa uma versao local READY e armazena backup. Em IBM_EXPORT_PENDING repita mesma chave e corpo.'});
add('07-autoria-ibm-manual','02-Consultar-operacao','GET','/ibm-writes/{{writeId}}');
add('07-autoria-ibm-manual','02-Listar-operacoes','GET','/projects/{{projectId}}/ibm-writes?page=0&size=20');
add('07-autoria-ibm-manual','03-Backup-remoto','GET','/ibm-writes/{{writeId}}/backup');
add('07-autoria-ibm-manual','04-Aplicar-Draft','POST','/ibm-writes/{{writeId}}/apply',null,{status:[200,202,409,422],doc:'ALTERA CONTEUDO REAL DA SKILL DRAFT. Revise a versao local e o backup antes de enviar. Repetir nao reenvia uma operacao ja despachada. Nao publica em Live.'});
add('07-autoria-ibm-manual','05-Reconciliar','POST','/ibm-writes/{{writeId}}/reconcile',null,{status:[200,202,409,422],doc:'Consulta estado e conteudo IBM; nunca reenvia atualizacao. UNKNOWN preserva bloqueio da skill ate confirmacao.'});
add('90-administracao-manual','01-Criar-usuario','POST','/admin/users',{name:'Usuario Bruno',email:'{{testUserEmail}}',password:'{{testUserPassword}}',admin:false},{capture:'userId',status:[201]});
add('90-administracao-manual','02-Consultar-usuario','GET','/admin/users/{{userId}}');
add('90-administracao-manual','03-Atualizar-usuario','PATCH','/admin/users/{{userId}}',{name:'Usuario Bruno atualizado',admin:false,active:true});
add('90-administracao-manual','04-Atualizar-minha-conta','PATCH','/account',{name:'Nome atualizado'});
add('90-administracao-manual','05-Alterar-minha-senha','PUT','/account/password',{currentPassword:'{{password}}',newPassword:'{{newPassword}}'},{status:[204],doc:'Altera a senha do usuario autenticado e invalida sessoes. Execute somente por escolha explicita.'});
for(const [name,url] of [['Remover-membro','/portfolios/{{portfolioId}}/members/{{userId}}'],['Arquivar-fluxo','/flows/{{flowId}}'],['Desativar-destino','/targets/{{targetId}}'],['Desativar-integracao','/integrations/{{integrationId}}'],['Arquivar-projeto','/projects/{{projectId}}'],['Arquivar-portfolio','/portfolios/{{portfolioId}}']]) add('99-encerramento-manual',name,'DELETE',url,null,{status:[204]});
add('99-encerramento-manual','Logout','POST','/auth/logout',null,{status:[204]});
fs.writeFileSync(path.join(root,'ROUTES.md'),'# Rotas da collection\n\nGerado de scripts/generate-bruno.cjs. Consulte README.md antes de executar.\n\n| Pasta | Requisicao | Metodo | Rota |\n|---|---|---|---|\n'+inventory.map(r=>'| '+r.join(' | ')+' |').join('\n')+'\n');
console.log(`Generated ${inventory.length} requests`);
