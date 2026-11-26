package net.talaatharb.expanse.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.springframework.web.bind.annotation.*;

public interface WorkbenchApi {
    @GetMapping("/api/v1/capabilities")
    List<Capability> capabilities();

    @GetMapping("/api/v1/projects")
    List<Summary> projects(@RequestParam("offset") int offset, @RequestParam("limit") int limit);

    @PostMapping("/api/v1/projects")
    Snapshot create(@Valid @RequestBody CreateProject request);

    @GetMapping("/api/v1/projects/{id}")
    Snapshot project(@PathVariable("id") UUID id);

    @PostMapping("/api/v1/projects/{id}/components")
    Snapshot place(@PathVariable("id") UUID id, @Valid @RequestBody PlaceComponent request);

    @PostMapping("/api/v1/projects/{id}/nets")
    Snapshot connect(@PathVariable("id") UUID id, @Valid @RequestBody ConnectPins request);

    @GetMapping("/api/v1/projects/{id}/diagnostics")
    ValidationReport validate(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/library")
    List<DefinitionView> search(@RequestParam("query") String query);

    @GetMapping("/api/v1/projects/{id}/document")
    Document exportProject(@PathVariable("id") UUID id);

    @PostMapping("/api/v1/projects/import")
    Snapshot importProject(@Valid @RequestBody Document document);

    @PostMapping("/api/v1/projects/{id}/changes/preview")
    ChangeResult previewChanges(@PathVariable("id") UUID id, @Valid @RequestBody ChangeSet changes);

    @PostMapping("/api/v1/projects/{id}/changes")
    ChangeResult applyChanges(@PathVariable("id") UUID id, @Valid @RequestBody ChangeSet changes);

    @GetMapping("/api/v1/projects/{id}/history")
    HistoryView history(@PathVariable("id") UUID id);

    @PostMapping("/api/v1/projects/{id}/history/undo")
    ChangeResult undo(@PathVariable("id") UUID id, @Valid @RequestBody HistoryStep request);

    @PostMapping("/api/v1/projects/{id}/history/redo")
    ChangeResult redo(@PathVariable("id") UUID id, @Valid @RequestBody HistoryStep request);

    @PostMapping("/api/v1/projects/{id}/simulations")
    SimulationReport simulate(@PathVariable("id") UUID id, @Valid @RequestBody SimulationRequest request);

    @GetMapping("/api/v1/projects/{id}/board/analysis")
    BoardAnalysis analyzeBoard(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/projects/{id}/assembly")
    AssemblyView assembly(@PathVariable("id") UUID id);

    @PutMapping("/api/v1/projects/{id}/document")
    Snapshot replaceDocument(@PathVariable("id") UUID id, @Valid @RequestBody ReplaceDocument request);

    @PostMapping("/api/v1/projects/import-copy")
    Snapshot importCopy(@Valid @RequestBody Document document);

    @DeleteMapping("/api/v1/projects/{id}")
    void delete(@PathVariable("id") UUID id, @RequestParam("expectedRevision") long expectedRevision);
}