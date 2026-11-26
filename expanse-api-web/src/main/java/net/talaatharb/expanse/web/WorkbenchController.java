package net.talaatharb.expanse.web;

import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.application.ProjectService;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WorkbenchController implements WorkbenchApi {
    private final ProjectService service;

    public WorkbenchController(ProjectService service) {
        this.service = service;
    }

    @Override
    public List<Capability> capabilities() {
        return service.capabilities();
    }

    @Override
    public List<Summary> projects(int offset, int limit) {
        return service.list(offset, limit);
    }

    @Override
    public Snapshot create(CreateProject request) {
        return service.create(request);
    }

    @Override
    public Snapshot project(UUID id) {
        return service.get(id);
    }

    @Override
    public Snapshot place(UUID id, PlaceComponent request) {
        return service.place(id, request);
    }

    @Override
    public Snapshot connect(UUID id, ConnectPins request) {
        return service.connect(id, request);
    }

    @Override
    public ValidationReport validate(UUID id) {
        return service.validate(id);
    }

    @Override
    public List<DefinitionView> search(String query) {
        return service.search(query);
    }

    @Override
    public Document exportProject(UUID id) {
        return service.exportProject(id);
    }

    @Override
    public Snapshot importProject(Document document) {
        return service.importProject(document);
    }

    @Override
    public ChangeResult previewChanges(UUID id, ChangeSet changes) {
        return service.preview(id, changes);
    }

    @Override
    public ChangeResult applyChanges(UUID id, ChangeSet changes) {
        return service.apply(id, changes);
    }

    @Override
    public SimulationReport simulate(UUID id, SimulationRequest request) {
        return service.simulate(id, request);
    }

    @Override
    public BoardAnalysis analyzeBoard(UUID id) {
        return service.analyzeBoard(id);
    }

    @Override
    public AssemblyView assembly(UUID id) {
        return service.assembly(id);
    }

    @Override
    public Snapshot replaceDocument(UUID id, ReplaceDocument request) {
        return service.replaceDocument(id, request);
    }

    @Override
    public Snapshot importCopy(Document document) {
        return service.importCopy(document);
    }

    @Override
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(UUID id, long expectedRevision) {
        service.delete(id, expectedRevision);
    }
}