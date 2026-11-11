package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.EditorDtos.*;
import com.tabula.tabula_backend.flow.dto.FlowDtos.FlowType;
import com.tabula.tabula_backend.flow.model.Flow;
import com.tabula.tabula_backend.flow.repository.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class EditorVersionService {
    public static final String EMPTY="{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"variables\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final FlowRepository flows;
    private final FlowMetadataService metadata;
    private final PortfolioService portfolios;
    private final IntegrationService integrations;
    private final DiagramDocumentStore documents;
    private final FlowJsonCodec codec;
    private final IbmFlowExportService ibm;
    private final ClassicDialogTranslator translator;
    private final SkillWriteService writes;
    private final ObjectMapper json;
    private final FlowService legacy;
    private final FlowLayoutService layouts;

    public EditorVersionService(JdbcTemplate sql, PlatformTransactionManager manager, FlowRepository flows,
            FlowMetadataService metadata, PortfolioService portfolios, IntegrationService integrations,
            DiagramDocumentStore documents, FlowJsonCodec codec, IbmFlowExportService ibm,
            ClassicDialogTranslator translator, SkillWriteService writes, ObjectMapper json, FlowService legacy, FlowLayoutService layouts) {
        this.sql=sql; tx=new TransactionTemplate(manager); this.flows=flows; this.metadata=metadata;
        this.portfolios=portfolios; this.integrations=integrations; this.documents=documents; this.codec=codec;
        this.ibm=ibm; this.translator=translator; this.writes=writes; this.json=json;
        this.legacy=legacy; this.layouts=layouts;
    }

    public boolean manages(String flowId) {
        return sql.queryForObject("SELECT COUNT(*) FROM diagram_flows WHERE flow_id=?",Integer.class,flowId)>0;
    }

    public boolean hasVersion(String id) { return sql.queryForObject("SELECT COUNT(*) FROM diagram_versions WHERE id=?",Integer.class,id)>0; }

    /** Copy legacy versions and unsaved working content before marking the flow as editor-managed. */
    public List<Version> migrate(AccountPrincipal actor,String flowId) {
        var flow=metadata.editable(actor,flowId);
        if(manages(flowId)) return list(actor,flowId,0,100).content();
        if(flow.type()==FlowType.ACTION) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ACTION_EDITOR_NOT_IMPLEMENTED");
        String portfolio=portfolios.project(actor,flow.projectId()).portfolioId();
        com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.View layout=null;
        try { layout=layouts.find(actor,flowId); } catch(FlowException ex) { if(!Set.of("LAYOUT_NOT_FOUND","LOCAL_CANVAS_USE_DRAFT").contains(ex.code())) throw ex; }
        var originals=new ArrayList<com.tabula.tabula_backend.flow.dto.FlowDtos.VersionView>();
        int page=0;
        while(true) {
            var batch=metadata.versions(actor,flowId,page++,100); originals.addAll(batch.content());
            if(page>=batch.totalPages()) break;
        }
        originals.sort(Comparator.comparingInt(com.tabula.tabula_backend.flow.dto.FlowDtos.VersionView::number));
        var copies=new ArrayList<Document>(); var names=new ArrayList<String>();
        for(var v:originals) {
            if(!"READY".equals(v.state())) throw error(HttpStatus.CONFLICT,"FLOW_VERSION_NOT_READY");
            String raw=legacy.export(actor,v.id());
            copies.add(document(UUID.randomUUID().toString(),flowId,flow.projectId(),portfolio,legacyCanvas(raw,flow.type(),layout)));
            names.add("Versão "+v.number());
        }
        try {
            var draft=legacy.draft(actor,flowId);
            String raw=legacyCanvas(draft.rawJson(),flow.type(),layout);
            if(copies.isEmpty()||!codec.checksum(raw).equals(copies.getLast().checksum())) {
                copies.add(document(UUID.randomUUID().toString(),flowId,flow.projectId(),portfolio,raw)); names.add("Trabalho anterior");
            }
        } catch(FlowException ex) { if(!"DRAFT_NOT_FOUND".equals(ex.code())) throw ex; }
        if(copies.isEmpty()) { copies.add(document(UUID.randomUUID().toString(),flowId,flow.projectId(),portfolio,EMPTY)); names.add("Versão 1"); }
        copies.forEach(documents::put);
        return tx.execute(status -> {
            metadata.editable(actor,flowId);
            // Lock the common parent even before the editor marker exists.
            sql.queryForObject("SELECT id FROM flows WHERE id=? FOR UPDATE",String.class,flowId);
            if(manages(flowId)) return list(actor,flowId,0,100).content();
            sql.update("INSERT INTO diagram_flows(flow_id,target_id) VALUES (?,NULL)",flowId);
            for(int i=0;i<copies.size();i++) insert(copies.get(i),i+1,names.get(i),actor.id(),"migration:"+copies.get(i).versionId(),copies.get(i).checksum());
            return copies.stream().map(d->version(actor,d.versionId())).toList();
        });
    }

    @SuppressWarnings("unchecked")
    private String legacyCanvas(String raw,FlowType type,com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.View layout) {
        if(type==FlowType.LOCAL) return raw;
        var skill=(Map<String,Object>)json.readerFor(Object.class).with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                tools.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw);
        return translator.importSkill(skill,layout);
    }

    public Created create(AccountPrincipal actor,String projectId,Create input,String requestKey) {
        return createContent(actor,projectId,input,requestKey,EMPTY);
    }

    @SuppressWarnings("unchecked")
    public Created upload(AccountPrincipal actor,String projectId,com.tabula.tabula_backend.flow.dto.FlowDtos.Upload input,String key) {
        portfolios.requireProjectEditing(actor,projectId);
        if(input.type()==null) throw error(HttpStatus.BAD_REQUEST,"INVALID_FLOW_FORMAT");
        if(input.type()==FlowType.ACTION) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ACTION_EDITOR_NOT_IMPLEMENTED");
        String raw=input.json();
        codec.validate(raw,input.type());
        if(input.type()==FlowType.DIALOG) {
            var skill=(Map<String,Object>)json.readerFor(Object.class).with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    tools.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw);
            raw=translator.importSkill(skill);
        }
        return createContent(actor,projectId,new Create(input.name(),input.targetId()),key,raw);
    }

    private Created createContent(AccountPrincipal actor,String projectId,Create input,String requestKey,String raw) {
        return createContent(actor,projectId,input,requestKey,raw,null);
    }

    public Created createImported(AccountPrincipal actor,String projectId,com.tabula.tabula_backend.flow.dto.FlowDtos.IbmImport input,String key) {
        portfolios.requireProjectEditing(actor,projectId); FlowService.key(key);
        if(input.type()!=FlowType.DIALOG) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ACTION_EDITOR_NOT_IMPLEMENTED");
        String hash=codec.requestHash(Map.of("operation","IMPORT_EDITOR_FLOW","input",input));
        var existing=replay(projectId,key,hash); if(existing.isPresent()) return created(existing.get());
        target(actor,projectId,input.targetId().toString());
        var exported=ibm.export(actor,projectId,input.targetId().toString(),"DIALOG");
        String label=input.name()!=null?input.name():"Dialog IBM";
        return createContent(actor,projectId,new Create(label,input.targetId()),key,translator.importSkill(exported),hash);
    }

    private Created createContent(AccountPrincipal actor,String projectId,Create input,String requestKey,String raw,String operationHash) {
        var project=portfolios.requireProjectEditing(actor,projectId);
        String key=FlowService.key(requestKey);
        String hash=operationHash!=null?operationHash:codec.requestHash(Map.of("operation","CREATE_EDITOR_FLOW","input",input,"checksum",codec.checksum(raw)));
        var replay=replay(projectId,key,hash);
        if(replay.isPresent()) return created(replay.get());
        if(input.targetId()!=null) target(actor,projectId,input.targetId().toString());
        var flow=new Flow(projectId,name(input.name()),"LOCAL");
        String versionId=UUID.randomUUID().toString();
        var document=document(versionId,flow.getId(),projectId,project.portfolioId(),raw);
        documents.put(document);
        return tx.execute(status -> {
            portfolios.requireProjectEditing(actor,projectId);
            var existing=replay(projectId,key,hash);
            if(existing.isPresent()) return created(existing.get());
            if(input.targetId()!=null) target(actor,projectId,input.targetId().toString());
            flows.saveAndFlush(flow);
            sql.update("INSERT INTO diagram_flows(flow_id,target_id) VALUES (?,?)",flow.getId(),input.targetId()==null?null:input.targetId().toString());
            insert(document,1,"Versão 1",actor.id(),key,hash);
            return created(version(actor,versionId));
        });
    }

    public Binding binding(AccountPrincipal actor,String flowId) {
        metadata.find(actor,flowId);
        return binding(flowId);
    }

    public Binding bind(AccountPrincipal actor,String flowId,String targetId) {
        var flow=metadata.editable(actor,flowId);
        target(actor,flow.projectId(),targetId);
        return tx.execute(status -> {
            metadata.editable(actor,flowId); lock(flowId);
            sql.update("UPDATE diagram_flows SET target_id=? WHERE flow_id=?",targetId,flowId);
            return binding(flowId);
        });
    }

    public Version version(AccountPrincipal actor,String id) {
        var row=row(id);
        metadata.find(actor,row.version().flowId());
        return row.version();
    }

    public PageView<Version> list(AccountPrincipal actor,String flowId,int page,int size) {
        metadata.find(actor,flowId); binding(flowId);
        if(page<0||size<1||size>100) throw error(HttpStatus.BAD_REQUEST,"INVALID_PAGE");
        long total=sql.queryForObject("SELECT COUNT(*) FROM diagram_versions WHERE flow_id=?",Long.class,flowId);
        var content=sql.query("SELECT * FROM diagram_versions WHERE flow_id=? ORDER BY version_number LIMIT ? OFFSET ?",
                (rs,n)->map(rs).version(),flowId,size,(long)page*size);
        return new PageView<>(content,page,size,total,(int)Math.ceil((double)total/size));
    }

    public Content read(AccountPrincipal actor,String id) {
        var r=row(id); var v=r.version();
        var flow=metadata.find(actor,v.flowId());
        String portfolio=portfolios.project(actor,flow.projectId()).portfolioId();
        var d=documents.find(portfolio,r.documentId()).orElseThrow(()->error(HttpStatus.SERVICE_UNAVAILABLE,"DIAGRAM_DOCUMENT_MISSING"));
        if(!d.id().equals(r.documentId())||!d.versionId().equals(id)||!d.flowId().equals(v.flowId())
                ||!d.projectId().equals(v.projectId())||!d.portfolioId().equals(portfolio)
                ||!d.checksum().equals(v.checksum())||!codec.checksum(d.rawJson()).equals(v.checksum())) {
            throw error(HttpStatus.CONFLICT,"DIAGRAM_INTEGRITY_ERROR");
        }
        return new Content(v,d.rawJson());
    }

    public Content save(AccountPrincipal actor,String id,String etag,String raw) {
        var v=version(actor,id); metadata.editable(actor,v.flowId());
        long revision=revision(etag);
        if(v.revision()!=revision) throw error(HttpStatus.PRECONDITION_FAILED,"VERSION_REVISION_MISMATCH");
        var flow=metadata.find(actor,v.flowId());
        String portfolio=portfolios.project(actor,flow.projectId()).portfolioId();
        var d=document(id,v.flowId(),v.projectId(),portfolio,raw);
        documents.put(d);
        tx.executeWithoutResult(status -> {
            metadata.editable(actor,v.flowId());
            if(sql.update("UPDATE diagram_versions SET document_id=?,checksum=?,revision=revision+1 WHERE id=? AND revision=?",
                    d.id(),d.checksum(),id,revision)!=1) throw error(HttpStatus.PRECONDITION_FAILED,"VERSION_REVISION_MISMATCH");
        });
        return read(actor,id);
    }

    public Version rename(AccountPrincipal actor,String id,String etag,String newName) {
        var v=version(actor,id); metadata.editable(actor,v.flowId());
        long revision=revision(etag); String checked=name(newName);
        tx.executeWithoutResult(status -> {
            metadata.editable(actor,v.flowId());
            if(sql.update("UPDATE diagram_versions SET name=?,revision=revision+1 WHERE id=? AND revision=?",checked,id,revision)!=1)
                throw error(HttpStatus.PRECONDITION_FAILED,"VERSION_REVISION_MISMATCH");
        });
        return version(actor,id);
    }

    public Version add(AccountPrincipal actor,String flowId,NewVersion input,String requestKey) {
        var flow=metadata.editable(actor,flowId); binding(flowId);
        String key=FlowService.key(requestKey);
        String hash=codec.requestHash(Map.of("operation","NEW_EDITOR_VERSION","flowId",flowId,"input",input));
        var replay=replay(flow.projectId(),key,hash);
        if(replay.isPresent()) return replay.get();
        String raw=EMPTY;
        if(input.sourceVersionId()!=null) {
            var source=read(actor,input.sourceVersionId().toString());
            if(!source.version().flowId().equals(flowId)) throw error(HttpStatus.NOT_FOUND,"FLOW_VERSION_MISMATCH");
            raw=source.rawJson();
        }
        String portfolio=portfolios.project(actor,flow.projectId()).portfolioId();
        var document=document(UUID.randomUUID().toString(),flowId,flow.projectId(),portfolio,raw);
        documents.put(document);
        return tx.execute(status -> {
            metadata.editable(actor,flowId); lock(flowId);
            var existing=replay(flow.projectId(),key,hash);
            if(existing.isPresent()) return existing.get();
            int number=sql.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM diagram_versions WHERE flow_id=?",Integer.class,flowId);
            insert(document,number,name(input.name()),actor.id(),key,hash);
            return version(actor,document.versionId());
        });
    }

    public Content importIbm(AccountPrincipal actor,String id,String etag) {
        var v=version(actor,id); metadata.editable(actor,v.flowId());
        if(v.revision()!=revision(etag)) throw error(HttpStatus.PRECONDITION_FAILED,"VERSION_REVISION_MISMATCH");
        String targetId=boundTarget(actor,v.flowId(),v.projectId());
        var exported=ibm.export(actor,v.projectId(),targetId,"DIALOG");
        return save(actor,id,etag,translator.importSkill(exported));
    }

    public com.tabula.tabula_backend.integration.dto.SkillWriteDtos.View publish(AccountPrincipal actor,String id,String etag,String key,boolean confirmed) {
        if(!confirmed) throw error(HttpStatus.BAD_REQUEST,"IBM_OVERWRITE_CONFIRMATION_REQUIRED");
        var content=read(actor,id); var v=content.version(); metadata.editable(actor,v.flowId());
        if(v.revision()!=revision(etag)) throw error(HttpStatus.PRECONDITION_FAILED,"VERSION_REVISION_MISMATCH");
        String target=boundTarget(actor,v.flowId(),v.projectId());
        return writes.prepareDiagram(actor,v.projectId(),target,id,translator.exportSkill(content.rawJson()),key);
    }

    private String boundTarget(AccountPrincipal actor,String flowId,String projectId) {
        String id=binding(flowId).targetId();
        if(id==null) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IBM_FLOW_NOT_LINKED");
        target(actor,projectId,id); return id;
    }
    private void target(AccountPrincipal actor,String projectId,String id) {
        var target=integrations.targetForExport(actor,id);
        if(!target.projectId().equals(projectId)) throw error(HttpStatus.NOT_FOUND,"TARGET_PROJECT_MISMATCH");
        var c=target.configuration();
        if(c.environmentType()!=EnvironmentType.DRAFT||c.assistantId()==null||c.dialogSkillId()==null)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,"IBM_DIALOG_DRAFT_TARGET_REQUIRED");
    }
    private Binding binding(String flowId) {
        return sql.query("SELECT * FROM diagram_flows WHERE flow_id=?",(rs,n)->new Binding(flowId,rs.getString("target_id")),flowId)
                .stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"EDITOR_FLOW_NOT_FOUND"));
    }
    private void lock(String flowId) {
        sql.queryForObject("SELECT flow_id FROM diagram_flows WHERE flow_id=? FOR UPDATE",String.class,flowId);
    }
    private Created created(Version v) { return new Created(v.flowId(),v.id(),v,binding(v.flowId()).targetId()); }
    private Optional<Version> replay(String projectId,String key,String hash) {
        return sql.query("SELECT * FROM diagram_versions WHERE project_id=? AND request_key=?",(rs,n)-> {
            if(!hash.equals(rs.getString("request_hash"))) throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return map(rs).version();
        },projectId,key).stream().findFirst();
    }
    private Document document(String version,String flow,String project,String portfolio,String raw) {
        String checksum=codec.validate(raw,FlowType.LOCAL);
        return new Document(UUID.randomUUID().toString(),version,flow,project,portfolio,checksum,raw);
    }
    private void insert(Document d,int number,String name,String author,String key,String hash) {
        sql.update("INSERT INTO diagram_versions(id,flow_id,project_id,version_number,name,revision,document_id,checksum,author_id,created_at,request_key,request_hash) VALUES (?,?,?,?,?,1,?,?,?,?,?,?)",
                d.versionId(),d.flowId(),d.projectId(),number,name,d.id(),d.checksum(),author,Instant.now().toString(),key,hash);
    }
    private Row row(String id) {
        return sql.query("SELECT * FROM diagram_versions WHERE id=?",(rs,n)->map(rs),id).stream().findFirst()
                .orElseThrow(()->error(HttpStatus.NOT_FOUND,"DIAGRAM_VERSION_NOT_FOUND"));
    }
    private Row map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Row(new Version(r.getString("id"),r.getString("flow_id"),r.getString("project_id"),r.getInt("version_number"),
                r.getString("name"),r.getLong("revision"),r.getString("checksum"),r.getString("author_id"),r.getString("created_at")),r.getString("document_id"));
    }
    private record Row(Version version,String documentId) { }
    private static long revision(String etag) {
        if(etag==null) throw error(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REVISION_REQUIRED");
        if(!etag.matches("\"[1-9][0-9]{0,17}\"")) throw error(HttpStatus.BAD_REQUEST,"INVALID_VERSION_ETAG");
        return Long.parseLong(etag.substring(1,etag.length()-1));
    }
    private String name(String value) {
        if(value==null||value.isBlank()||value.strip().length()>120) throw error(HttpStatus.BAD_REQUEST,"INVALID_VERSION_NAME");
        return value.strip();
    }
    private static FlowException error(HttpStatus status,String code) { return new FlowException(status,code,"Não foi possível concluir a operação do diagrama. Consulte o código."); }
}
