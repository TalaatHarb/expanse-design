package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.connector.ConnectorException;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSessionTest {
    @TempDir
    Path directory;
    private final WorkbenchApi api = mock(WorkbenchApi.class);
    private final ProjectSession session = new ProjectSession(api);
    private final UUID id = UUID.randomUUID();

    private Snapshot snapshot(UUID projectId, long revision) {
        return new Snapshot(projectId, "Circuit", revision, List.of(), List.of(), List.of(), null);
    }

    private static String document(UUID projectId, long revision) {
        return "{\"schemaVersion\":\"0.2\",\"id\":\"" + projectId + "\",\"name\":\"Circuit\",\"revision\":" + revision + "}";
    }

    private ChangeResult applied(long revision) {
        return new ChangeResult(id, revision - 1, revision, true, List.of(), List.of(), List.of(), snapshot(id, revision));
    }

    @Test
    void editsMakeTheSessionDirtyUntilSavedAndSaveWritesTheServerDocument() throws Exception {
        when(api.create(any())).thenReturn(snapshot(id, 0));
        when(api.applyChanges(eq(id), any())).thenReturn(applied(1));
        when(api.exportProject(id)).thenReturn(new Document(document(id, 1)));

        session.create("Circuit");
        assertThat(session.dirty()).isFalse();
        session.apply(List.of(new SyncBoardOp()));
        assertThat(session.dirty()).isTrue();
        verify(api).applyChanges(id, new ChangeSet(0, List.of(new SyncBoardOp())));

        Path file = directory.resolve("circuit.json");
        session.saveAs(file);
        assertThat(session.dirty()).isFalse();
        assertThat(session.file()).isEqualTo(file.toAbsolutePath());
        assertThat(Files.readString(file)).isEqualTo(document(id, 1));
    }

    @Test
    void revisionConflictReloadsTheLatestServerRevisionAndRethrows() {
        when(api.create(any())).thenReturn(snapshot(id, 0));
        when(api.applyChanges(eq(id), any())).thenThrow(new ConnectorException(409,
                new ApiError("REVISION_CONFLICT", "stale", 3L)));
        when(api.project(id)).thenReturn(snapshot(id, 3));
        session.create("Circuit");

        assertThatThrownBy(() -> session.apply(List.of(new SyncBoardOp()))).isInstanceOf(ConnectorException.class);
        assertThat(session.snapshot().revision()).isEqualTo(3);
    }

    @Test
    void openingAFileUnknownToTheServerImportsItAndIsClean() throws Exception {
        Path file = directory.resolve("new.json");
        Files.writeString(file, document(id, 4));
        when(api.project(id)).thenThrow(new ConnectorException(404, new ApiError("NOT_FOUND", "missing", null)));
        when(api.importProject(any())).thenReturn(snapshot(id, 4));

        var plan = session.inspect(file);
        assertThat(plan.existing()).isEmpty();
        assertThat(plan.info().revision()).isEqualTo(4);
        session.open(plan, null);

        verify(api).importProject(new Document(document(id, 4)));
        assertThat(session.file()).isEqualTo(file.toAbsolutePath());
        assertThat(session.dirty()).isFalse();
    }

    @Test
    void openingAFileKnownToTheServerHonoursTheChoice() throws Exception {
        Path file = directory.resolve("known.json");
        Files.writeString(file, document(id, 2));
        when(api.project(id)).thenReturn(snapshot(id, 5));
        var plan = session.inspect(file);
        assertThat(plan.existing()).isPresent();

        when(api.replaceDocument(eq(id), any())).thenReturn(snapshot(id, 6));
        session.open(plan, ProjectSession.OpenChoice.REPLACE);
        verify(api).replaceDocument(id, new ReplaceDocument(5, document(id, 2)));
        assertThat(session.dirty()).isFalse();

        UUID copy = UUID.randomUUID();
        when(api.importCopy(any())).thenReturn(snapshot(copy, 0));
        session.open(plan, ProjectSession.OpenChoice.COPY);
        assertThat(session.snapshot().id()).isEqualTo(copy);
        assertThat(session.file()).isNull();

        session.open(plan, ProjectSession.OpenChoice.EXISTING);
        assertThat(session.snapshot().revision()).isEqualTo(5);
        assertThat(session.file()).isEqualTo(file.toAbsolutePath());
        assertThat(session.dirty()).as("server is ahead of the file").isTrue();
    }

    @Test
    void inspectRejectsFilesThatAreNotProjects() throws Exception {
        Path notJson = Files.writeString(directory.resolve("a.json"), "not json");
        Path noId = Files.writeString(directory.resolve("b.json"), "{\"name\":\"x\"}");
        assertThatThrownBy(() -> session.inspect(notJson)).hasMessageContaining("Not a JSON project file");
        assertThatThrownBy(() -> session.inspect(noId)).hasMessageContaining("missing project id");
        verifyNoInteractions(api);
    }
}
