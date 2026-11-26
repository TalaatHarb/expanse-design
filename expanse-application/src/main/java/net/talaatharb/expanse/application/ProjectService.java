package net.talaatharb.expanse.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.talaatharb.expanse.assembly.AssemblyBuilder;
import net.talaatharb.expanse.domain.Design;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.dto.ApiModels.*;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.pcb.BoardAnalyzer;
import net.talaatharb.expanse.pcb.BoardEditor;
import net.talaatharb.expanse.pcb.PcbGeometry;
import net.talaatharb.expanse.schematic.SchematicEditor;
import net.talaatharb.expanse.simulation.CircuitSimulator;
import net.talaatharb.expanse.simulation.LogicSimulator;
import net.talaatharb.expanse.validation.DesignValidator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ProjectService {
    private static final DesignViewMapper VIEWS = DesignViewMapper.INSTANCE;
    private final ProjectRepository repository;
    private final ProjectJson json;
    private final ComponentLibrary library;
    private final DesignValidator validator;
    private final ChangeEngine engine;
    private final CircuitSimulator simulator = new CircuitSimulator();
    private final LogicSimulator logicSimulator = new LogicSimulator();
    private final BoardAnalyzer boardAnalyzer = new BoardAnalyzer();
    private final AssemblyBuilder assemblyBuilder = new AssemblyBuilder();

    public ProjectService(ProjectRepository repository, ProjectJson json, ComponentLibrary library,
                          SchematicEditor editor, DesignValidator validator) {
        this.repository = repository;
        this.json = json;
        this.library = library;
        this.validator = validator;
        this.engine = new ChangeEngine(library, editor, new BoardEditor());
    }

    public List<Summary> list(int offset, int limit) {
        Design.require(offset >= 0 && limit > 0 && limit <= 100, "Use offset >= 0 and limit between 1 and 100");
        return repository.list(offset, limit).stream().map(VIEWS::summary).toList();
    }

    public Snapshot create(CreateProject request) {
        Project project = Project.create(request.name());
        repository.insert(project);
        LOG.info("action=project.create project={} revision=0 outcome=success", project.id());
        return snapshot(project);
    }

    public Snapshot get(UUID id) {
        return snapshot(repository.get(id));
    }

    public Snapshot place(UUID id, PlaceComponent request) {
        return apply(id, new ChangeSet(request.expectedRevision(), List.of(new PlaceOp(request.definitionId(),
                request.reference(), request.xMm(), request.yMm(), 0, Map.of(), request.sheetId())))).snapshot();
    }

    public Snapshot connect(UUID id, ConnectPins request) {
        Design.require(request.pinIds() != null, "Select at least two pins");
        return apply(id, new ChangeSet(request.expectedRevision(), List.of(new ConnectOp(request.name(),
                request.pinIds().stream().map(UUID::toString).toList())))).snapshot();
    }

    /** Runs the change set in memory; nothing is persisted. */
    public ChangeResult preview(UUID id, ChangeSet changes) {
        Project current = repository.get(id);
        checkRevision(current, changes.expectedRevision());
        var outcome = engine.apply(current, changes.operations());
        return result(current, outcome, false);
    }

    /** Applies every operation as one revision, or none of them. */
    public ChangeResult apply(UUID id, ChangeSet changes) {
        String action = "project.apply_changes";
        try {
            Project current = repository.get(id);
            checkRevision(current, changes.expectedRevision());
            var outcome = engine.apply(current, changes.operations());
            repository.replace(outcome.project(), current.revision());
            LOG.info("action={} project={} revision={} operations={} outcome=success", action, id,
                    outcome.project().revision(), changes.operations().stream().map(ChangeEngine::name).toList());
            return result(current, outcome, true);
        } catch (DesignException e) {
            LOG.warn("action={} project={} expectedRevision={} outcome=rejected code={}", action, id,
                    changes.expectedRevision(), e.code());
            throw e;
        }
    }

    public HistoryView history(UUID id) {
        Project current = repository.get(id);
        var history = repository.revision(id, current.revision()).map(ProjectRepository.StoredRevision::history)
                .orElse(ProjectRepository.History.NONE);
        Long undo = retained(id, history.undoTo());
        Long redo = retained(id, history.redoTo());
        return new HistoryView(id, current.revision(), undo != null, redo != null, undo, redo);
    }

    /** Restores the content before the latest edit as a new revision; redo can bring the edit back. */
    public ChangeResult undo(UUID id, HistoryStep request) {
        return step(id, request.expectedRevision(), true);
    }

    /** Reapplies the most recently undone edit as a new revision. */
    public ChangeResult redo(UUID id, HistoryStep request) {
        return step(id, request.expectedRevision(), false);
    }

    private Long retained(UUID id, Long revision) {
        return revision != null && repository.revision(id, revision).isPresent() ? revision : null;
    }

    /*
     * Each revision links to the revision undo restores and the one redo restores. Undo at c to t writes a new
     * revision n with t's content, undo link undo(t) and redo link c; redo at c to t writes n with t's content,
     * undo link c and redo link redo(t). Ordinary edits link back to their base and clear redo.
     */
    private ChangeResult step(UUID id, long expectedRevision, boolean undo) {
        String action = undo ? "project.undo" : "project.redo";
        try {
            Project current = repository.get(id);
            checkRevision(current, expectedRevision);
            var history = repository.revision(id, current.revision()).map(ProjectRepository.StoredRevision::history)
                    .orElse(ProjectRepository.History.NONE);
            Long targetRevision = undo ? history.undoTo() : history.redoTo();
            var target = targetRevision == null ? null : repository.revision(id, targetRevision).orElse(null);
            if (target == null) {
                throw new DesignException(undo ? "NOTHING_TO_UNDO" : "NOTHING_TO_REDO",
                        undo ? "There is no earlier edit to undo" : "There is no undone edit to redo",
                        current.revision());
            }
            Project restored = target.project();
            Project next = new Project(restored.schemaVersion(), id, restored.name(),
                    Math.incrementExact(current.revision()), current.createdAt(),
                    Instant.now().isBefore(current.modifiedAt()) ? current.modifiedAt() : Instant.now(),
                    restored.sheets(), restored.components(), restored.nets(), restored.board(), restored.parts(),
                    restored.extensions());
            var links = undo ? new ProjectRepository.History(target.history().undoTo(), current.revision())
                    : new ProjectRepository.History(current.revision(), target.history().redoTo());
            repository.replace(next, current.revision(), links);
            LOG.info("action={} project={} revision={} restored={} outcome=success", action, id, next.revision(),
                    targetRevision);
            String change = (undo ? "Undid revision " + current.revision() : "Redid revision " + targetRevision)
                    + "; restored the content of revision " + targetRevision;
            return new ChangeResult(id, current.revision(), next.revision(), true, List.of(change), List.of(),
                    diagnostics(next), snapshot(next));
        } catch (DesignException e) {
            LOG.warn("action={} project={} expectedRevision={} outcome=rejected code={}", action, id,
                    expectedRevision, e.code());
            throw e;
        }
    }

    public ValidationReport validate(UUID id) {
        Project project = repository.get(id);
        return new ValidationReport(id, project.revision(), diagnostics(project));
    }

    public List<DefinitionView> search(String query) {
        return library.search(query == null ? "" : query).stream().map(VIEWS::definition).toList();
    }

    public SimulationReport simulate(UUID id, SimulationRequest request) {
        Project project = repository.get(id);
        double stop = request.stopTimeS() == null ? 0 : request.stopTimeS();
        var events = request.switchEvents() == null ? List.<CircuitSimulator.SwitchEvent>of()
                : request.switchEvents().stream()
                .map(e -> new CircuitSimulator.SwitchEvent(e.reference(), e.timeS(), e.state())).toList();
        if ("logic".equals(request.analysis())) {
            var logic = logicSimulator.simulate(project, stop, events);
            LOG.info("action=simulation.run project={} revision={} analysis=logic outcome=success", id,
                    project.revision());
            Map<String, List<LogicTransitionView>> series = new LinkedHashMap<>();
            logic.series().forEach((net, transitions) -> series.put(net, transitions.stream()
                    .map(t -> new LogicTransitionView(t.timeS(), t.level())).toList()));
            return new SimulationReport(id, project.revision(), "logic", logic.engine(), logic.models(), Map.of(),
                    Map.of(), Map.of(), List.of(), Map.of(), Map.of(), logic.levels(), series, logic.warnings(),
                    diagnostics(project));
        }
        var options = new CircuitSimulator.Options(request.analysis(), stop,
                request.timeStepS() == null ? 0 : request.timeStepS(),
                Boolean.TRUE.equals(request.zeroInitialConditions()), events);
        var result = simulator.simulate(project, options);
        LOG.info("action=simulation.run project={} revision={} analysis={} outcome=success", id,
                project.revision(), result.analysis());
        return new SimulationReport(id, project.revision(), result.analysis(), result.engine(), result.models(),
                result.nodeVoltages(), result.pinCurrents(), result.componentPower(), result.time(),
                result.voltageSeries(), result.currentSeries(), Map.of(), Map.of(), result.warnings(),
                diagnostics(project));
    }

    public BoardAnalysis analyzeBoard(UUID id) {
        Project project = repository.get(id);
        var report = boardAnalyzer.analyze(project);
        return new BoardAnalysis(id, project.revision(), report.pads().stream().map(VIEWS::pad).toList(),
                report.airwires().stream().map(a -> new AirwireView(a.netId(), a.netName(), a.from().pinId(),
                        a.from().reference() + "." + a.from().pinNumber(), a.from().xMm(), a.from().yMm(),
                        a.to().pinId(), a.to().reference() + "." + a.to().pinNumber(), a.to().xMm(),
                        a.to().yMm())).toList(),
                report.findings().stream().map(VIEWS::diagnostic).toList());
    }

    public AssemblyView assembly(UUID id) {
        Project project = repository.get(id);
        var scene = assemblyBuilder.build(project);
        return new AssemblyView(id, project.revision(), scene.boardWidthMm(), scene.boardHeightMm(),
                scene.boardThicknessMm(), scene.maxHeightMm(), scene.solids().stream().map(VIEWS::solid).toList(),
                scene.findings().stream().map(VIEWS::diagnostic).toList());
    }

    public Document exportProject(UUID id) {
        return new Document(json.write(repository.get(id)));
    }

    public Snapshot importProject(Document document) {
        Project project = json.read(document.json());
        repository.insert(project);
        LOG.info("action=project.import project={} revision={} outcome=success", project.id(), project.revision());
        return snapshot(project);
    }

    /** Imports a document under fresh identities, so it can coexist with the project it was copied from. */
    public Snapshot importCopy(Document document) {
        Project source = json.read(document.json());
        String text = json.write(source);
        for (UUID old : identities(source)) {
            text = text.replace(old.toString(), UUID.randomUUID().toString());
        }
        Project copy = json.read(text);
        Instant now = Instant.now();
        copy = new Project(copy.schemaVersion(), copy.id(), copy.name(), 0, now, now, copy.sheets(),
                copy.components(), copy.nets(), copy.board(), copy.parts(), copy.extensions());
        repository.insert(copy);
        LOG.info("action=project.import_copy source={} project={} outcome=success", source.id(), copy.id());
        return snapshot(copy);
    }

    /** Replaces the stored document (for example when reopening a saved file) as the next revision. */
    public Snapshot replaceDocument(UUID id, ReplaceDocument request) {
        Project current = repository.get(id);
        checkRevision(current, request.expectedRevision());
        Project incoming = json.read(request.json());
        if (!incoming.id().equals(id)) {
            throw new DesignException("INVALID_DOCUMENT", "The document belongs to a different project");
        }
        Project next = new Project(incoming.schemaVersion(), id, incoming.name(),
                Math.incrementExact(current.revision()), incoming.createdAt(),
                Instant.now().isBefore(incoming.createdAt()) ? incoming.createdAt() : Instant.now(),
                incoming.sheets(), incoming.components(), incoming.nets(), incoming.board(), incoming.parts(),
                incoming.extensions());
        repository.replace(next, current.revision());
        LOG.info("action=project.replace_document project={} revision={} outcome=success", id, next.revision());
        return snapshot(next);
    }

    public void delete(UUID id, long expectedRevision) {
        repository.delete(id, expectedRevision);
        LOG.info("action=project.delete project={} revision={} outcome=success", id, expectedRevision);
    }

    public List<Capability> capabilities() {
        return List.of(
                new Capability("project.lifecycle", true,
                        "Revisioned projects, canonical JSON 0.3 import/export with 0.1/0.2 migration"),
                new Capability("schematic.basic", true,
                        "R, L, C, sources, rails, ground, switches, BJTs and MOSFETs with typed parameters"),
                new Capability("schematic.wires", true, "Wire graphics with bends, free ends and junctions"),
                new Capability("parts.custom", true, "Project-local parts with pins, parameters, pads and bodies"),
                new Capability("design.validation", true, "Connectivity and electrical rule checks"),
                new Capability("changes.transactional", true, "Preview and apply batched operations atomically"),
                new Capability("history.undo", true, "Server-side undo/redo of the last "
                        + ProjectRepository.HISTORY_LIMIT + " revisions, shared by every client"),
                new Capability("simulation", true, CircuitSimulator.ENGINE + " " + CircuitSimulator.VERSION
                        + ": DC operating point and transient (backward Euler), limited device models, "
                        + "behavioural logic gates"),
                new Capability("simulation.logic", true, LogicSimulator.ENGINE + " " + LogicSimulator.VERSION
                        + ": event-driven 0/1/X/Z logic with propagation delays, clocks and switch events"),
                new Capability("pcb", true, "Two-layer placement, orthogonal routing, ratsnest and basic DRC"),
                new Capability("assembly3d", true, "Parametric bodies derived from the board; collision checks"),
                new Capability("mcp.read", true, "Separate stdio MCP bridge using the same authenticated API"),
                new Capability("mcp.mutate", true, "Preview/apply change sets; mutations configurable per bridge"));
    }

    private ChangeResult result(Project base, ChangeEngine.Outcome outcome, boolean applied) {
        return new ChangeResult(base.id(), base.revision(), outcome.project().revision(), applied,
                outcome.changes(), outcome.created(), diagnostics(outcome.project()), snapshot(outcome.project()));
    }

    private List<Diagnostic> diagnostics(Project project) {
        return validator.validate(project).stream().map(VIEWS::diagnostic).toList();
    }

    private static void checkRevision(Project current, long expected) {
        if (current.revision() != expected) {
            throw new DesignException("REVISION_CONFLICT", "The project changed; refresh before editing",
                    current.revision());
        }
    }

    private static List<UUID> identities(Project p) {
        List<UUID> ids = new ArrayList<>();
        ids.add(p.id());
        p.sheets().forEach(s -> ids.add(s.id()));
        p.components().forEach(c -> {
            ids.add(c.id());
            c.pins().forEach(pin -> ids.add(pin.id()));
        });
        p.nets().forEach(n -> {
            ids.add(n.id());
            n.wires().forEach(w -> ids.add(w.id()));
        });
        p.board().traces().forEach(t -> ids.add(t.id()));
        return ids;
    }

    private static Snapshot snapshot(Project project) {
        return VIEWS.snapshot(project);
    }
}
