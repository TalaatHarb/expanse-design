package net.talaatharb.expanse.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.connector.ConnectorException;
import net.talaatharb.expanse.dto.ApiModels.*;

/**
 * The open project, its backing file and unsaved-change tracking. The server persists every revision in its
 * database; "saving" writes the canonical project document to the user's file. Methods block and must run off the
 * JavaFX thread; the class is confined to a single worker thread.
 */
final class ProjectSession {
    enum OpenChoice {
        /** Overwrite the server copy with the file contents (new revision). */
        REPLACE,
        /** Import the file as a new project with fresh IDs. */
        COPY,
        /** Ignore the file contents and open the server copy. */
        EXISTING
    }

    /** A file that was read and inspected but not yet opened. */
    record OpenPlan(Path file, String json, ProjectFiles.DocumentInfo info, Optional<Snapshot> existing) {
    }

    private final WorkbenchApi api;
    private Snapshot snapshot;
    private Path file;
    private long savedRevision = -1;

    ProjectSession(WorkbenchApi api) {
        this.api = api;
    }

    Snapshot snapshot() {
        return snapshot;
    }

    Path file() {
        return file;
    }

    boolean isOpen() {
        return snapshot != null;
    }

    /** True when the project has revisions not yet written to its file (or was never saved to a file). */
    boolean dirty() {
        return snapshot != null && (file == null ? snapshot.revision() > 0 && snapshot.revision() != savedRevision
                : snapshot.revision() != savedRevision);
    }

    Snapshot create(String name) {
        return attach(api.create(new CreateProject(name)), null, -1);
    }

    /** Opens a project that already lives on the server, without a backing file. */
    Snapshot openFromServer(java.util.UUID id) {
        Snapshot opened = api.project(id);
        return attach(opened, null, opened.revision());
    }

    Snapshot reload() {
        snapshot = api.project(snapshot.id());
        return snapshot;
    }

    void close() {
        snapshot = null;
        file = null;
        savedRevision = -1;
    }

    ChangeResult apply(List<Operation> operations) {
        try {
            ChangeResult result = api.applyChanges(snapshot.id(), new ChangeSet(snapshot.revision(), operations));
            snapshot = result.snapshot();
            return result;
        } catch (ConnectorException e) {
            if ("REVISION_CONFLICT".equals(e.error().code())) {
                snapshot = api.project(snapshot.id());
            }
            throw e;
        }
    }

    ChangeResult preview(List<Operation> operations) {
        return api.previewChanges(snapshot.id(), new ChangeSet(snapshot.revision(), operations));
    }

    void save() throws IOException {
        if (file == null) {
            throw new IllegalStateException("Choose a file with Save As first");
        }
        saveAs(file);
    }

    void saveAs(Path target) throws IOException {
        Document document = api.exportProject(snapshot.id());
        ProjectFiles.save(target, document.json());
        file = target.toAbsolutePath();
        savedRevision = snapshot.revision();
    }

    /** Reads a file and checks whether its project ID already exists on the server. */
    OpenPlan inspect(Path path) throws IOException {
        String json = ProjectFiles.read(path);
        var info = ProjectFiles.peek(json);
        Optional<Snapshot> existing;
        try {
            existing = Optional.of(api.project(info.id()));
        } catch (ConnectorException e) {
            if (e.status() != 404) {
                throw e;
            }
            existing = Optional.empty();
        }
        return new OpenPlan(path.toAbsolutePath(), json, info, existing);
    }

    /** Opens an inspected file; {@code choice} is required only when the project already exists on the server. */
    Snapshot open(OpenPlan plan, OpenChoice choice) {
        if (plan.existing().isEmpty()) {
            Snapshot imported = api.importProject(new Document(plan.json()));
            return attach(imported, plan.file(), imported.revision());
        }
        Snapshot existing = plan.existing().get();
        return switch (choice) {
            case REPLACE -> {
                Snapshot replaced = api.replaceDocument(existing.id(),
                        new ReplaceDocument(existing.revision(), plan.json()));
                yield attach(replaced, plan.file(), replaced.revision());
            }
            case COPY -> attach(api.importCopy(new Document(plan.json())), null, -1);
            case EXISTING -> attach(existing, plan.file(),
                    existing.revision() == plan.info().revision() ? existing.revision() : -1);
        };
    }

    private Snapshot attach(Snapshot opened, Path backingFile, long saved) {
        snapshot = opened;
        file = backingFile;
        savedRevision = saved;
        return opened;
    }
}
