package com.tabula.tabula_backend.flow.controller;

import com.tabula.tabula_backend.flow.dto.EditorDtos.*;
import com.tabula.tabula_backend.flow.service.EditorVersionService;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class EditorVersionController {
    private final EditorVersionService service;
    public EditorVersionController(EditorVersionService service) { this.service=service; }

    @PostMapping("/flows/{id}/migrate-editor")
    public java.util.List<Version> migrate(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id) {
        return service.migrate(actor,id.toString());
    }

    @PostMapping("/projects/{id}/diagram-flows")
    public ResponseEntity<Created> create(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @Valid @RequestBody Create input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=service.create(actor,id.toString(),input,key);
        return ResponseEntity.created(URI.create("/api/v1/diagram-versions/"+result.versionId()+"/canvas")).body(result);
    }
    @GetMapping("/flows/{id}/ibm-binding")
    public Binding binding(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id) { return service.binding(actor,id.toString()); }
    @PutMapping("/flows/{id}/ibm-binding")
    public Binding bind(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@Valid @RequestBody Bind input) {
        return service.bind(actor,id.toString(),input.targetId().toString());
    }
    @GetMapping("/flows/{id}/editor-versions")
    public PageView<Version> list(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(actor,id.toString(),page,size);
    }
    @PostMapping("/flows/{id}/editor-versions")
    public ResponseEntity<Version> add(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @Valid @RequestBody NewVersion input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=service.add(actor,id.toString(),input,key);
        return ResponseEntity.created(URI.create("/api/v1/diagram-versions/"+result.id()+"/canvas")).eTag(tag(result)).body(result);
    }
    @GetMapping("/diagram-versions/{id}")
    public ResponseEntity<Version> version(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id) {
        var result=service.version(actor,id.toString()); return ResponseEntity.ok().eTag(tag(result)).cacheControl(CacheControl.noStore()).body(result);
    }
    @PatchMapping({"/diagram-versions/{id}","/versions/{id}"})
    public ResponseEntity<Version> rename(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String etag,@Valid @RequestBody Rename input) {
        var result=service.rename(actor,id.toString(),etag,input.name()); return ResponseEntity.ok().eTag(tag(result)).body(result);
    }
    @GetMapping({"/diagram-versions/{id}/canvas","/versions/{id}/canvas"})
    public ResponseEntity<String> read(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id) { return content(service.read(actor,id.toString())); }
    @PutMapping(value={"/diagram-versions/{id}/canvas","/versions/{id}/canvas"},consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> save(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestBody String raw) { return content(service.save(actor,id.toString(),etag,raw)); }
    @PostMapping({"/diagram-versions/{id}/ibm-import","/versions/{id}/ibm-import"})
    public ResponseEntity<String> importIbm(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String etag) { return content(service.importIbm(actor,id.toString(),etag)); }
    @PostMapping({"/diagram-versions/{id}/ibm-publications","/versions/{id}/ibm-publications"})
    public ResponseEntity<SkillWriteDtos.View> publish(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody Publish input) {
        var result=service.publish(actor,id.toString(),etag,key,input.confirmOverwrite());
        return ResponseEntity.status("READY".equals(result.state())?HttpStatus.CREATED:HttpStatus.ACCEPTED).body(result);
    }
    private static String tag(Version v) { return "\""+v.revision()+"\""; }
    private static ResponseEntity<String> content(Content c) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).eTag(tag(c.version())).body(c.rawJson());
    }
}
