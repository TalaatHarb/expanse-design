package net.talaatharb.expanse.desktop;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.dto.ApiModels.Operation;
import net.talaatharb.expanse.dto.ApiModels.Snapshot;

/** What editor panes need from the shell: the current project, transactional edits and background calls. */
interface Workbench {
    WorkbenchApi api();

    /** The current project, or {@code null}. */
    Snapshot project();

    /** Applies operations as one revision on the server, then refreshes every view. */
    void apply(List<Operation> operations);

    /** Runs a blocking call off the FX thread and delivers its result on the FX thread. */
    <T> void run(Callable<T> call, Consumer<T> onSuccess);

    /** Cross-probes a component across views; {@code null} clears the selection. */
    void select(UUID componentId);

    UUID selected();

    void status(String message);

    void error(String message);
}
