package com.tabula.tabula_backend.flow.controller;

import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.service.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class FlowController {
    private final FlowService service;
    private final FlowMetadataService metadata;
    public FlowController(FlowService service, FlowMetadataService metadata) { this.service = service; this.metadata = metadata; }
    @PostMapping("/projects/{id}/flows")
    public ResponseEntity<VersionView> upload(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Upload input, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return versionResult(service.upload(actor, id.toString(), input, key));
    }
    @PostMapping("/projects/{id}/imports")
    public ResponseEntity<VersionView> importIbm(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody IbmImport input, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return versionResult(service.importIbm(actor, id.toString(), input, key));
    }
    @GetMapping("/projects/{id}/flows")
    public PageView<View> list(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return metadata.list(actor, id.toString(), includeArchived, page, size);
    }
    @GetMapping("/flows/{id}")
    public View find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return metadata.find(actor, id.toString()); }
    @PatchMapping("/flows/{id}")
    public View rename(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Rename input) { return metadata.rename(actor, id.toString(), input.name()); }
    @DeleteMapping("/flows/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { metadata.archive(actor, id.toString()); }
    @GetMapping("/flows/{id}/versions")
    public PageView<VersionView> versions(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return metadata.versions(actor, id.toString(), page, size); }
    @GetMapping("/versions/{id}")
    public VersionView version(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return metadata.version(actor, id.toString()); }
    @GetMapping("/versions/{id}/export")
    public ResponseEntity<String> export(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"flow-" + id + ".json\"").body(service.export(actor, id.toString()));
    }
    @PostMapping("/versions/{id}/reconcile")
    public VersionView reconcile(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return service.reconcile(actor, id.toString()); }
    @PostMapping("/flows/{id}/draft")
    public ResponseEntity<String> startDraft(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @Valid @RequestBody StartDraft input) {
        return draftResult(service.startDraft(actor, id.toString(), input.versionId().toString()));
    }
    @GetMapping("/flows/{id}/draft")
    public ResponseEntity<String> draft(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return draftResult(service.draft(actor, id.toString())); }
    @PutMapping(value = "/flows/{id}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> updateDraft(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String revision, @RequestBody String rawJson) {
        return draftResult(service.updateDraft(actor, id.toString(), revision, rawJson));
    }
    @PostMapping("/flows/{id}/versions")
    public ResponseEntity<VersionView> snapshot(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String revision,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return versionResult(service.snapshotDraft(actor, id.toString(), revision, key));
    }
    private static ResponseEntity<VersionView> versionResult(VersionView v) {
        return ResponseEntity.status("READY".equals(v.state()) ? HttpStatus.CREATED : "PENDING".equals(v.state()) ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .location(URI.create("/api/v1/versions/" + v.id())).body(v);
    }
    private static ResponseEntity<String> draftResult(Draft d) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).eTag("\"" + d.revision() + "\"").body(d.rawJson());
    }
}
