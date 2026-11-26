package net.talaatharb.expanse.desktop;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import net.talaatharb.expanse.api.WorkbenchApi;
import net.talaatharb.expanse.connector.ConnectorException;
import net.talaatharb.expanse.connector.WorkbenchClient;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * Desktop shell: menus, the open-project lifecycle (new, open, save, save as) and the schematic, simulation, PCB and
 * 3D views. All server calls run on one worker thread; every edit is a transactional change set.
 */
public final class WorkbenchApplication extends Application implements Workbench {
    private static final int PAGE = 50;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "workbench-requests");
        thread.setDaemon(true);
        return thread;
    });
    private LocalServer localServer = new LocalServer();
    private volatile ConfigurableApplicationContext context;
    private WorkbenchApi api;
    private Exception startupFailure;
    private final HealthMonitor health = new HealthMonitor();
    private final Circle healthDot = new Circle(5, Color.GRAY);
    private final Label healthLabel = new Label("server", healthDot);
    private ProjectSession session;
    private Stage stage;
    private BorderPane root;
    private final TabPane tabs = new TabPane();
    private final Label status = new Label("Ready");
    private final Label projectLabel = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private SchematicPane schematic;
    private SimulationPane simulation;
    private PcbPane pcb;
    private AssemblyPane assembly;
    private Snapshot current;
    private Path currentFile;
    private boolean dirty;
    private UUID selected;
    private int pending;

    /** Session state captured on the worker thread and published to the FX thread. */
    private record State(Snapshot snapshot, Path file, boolean dirty, List<Diagnostic> diagnostics,
                         HistoryView history) {
    }

    private final MenuItem undoItem = item("Undo", "Shortcut+Z", () -> step(true));
    private final MenuItem redoItem = item("Redo", "Shortcut+Y", () -> step(false));

    @Override
    public void init() {
        try {
            connect();
        } catch (Exception e) {
            // start() offers File > Settings instead of failing to launch with an unusable configuration.
            startupFailure = e;
        }
    }

    /**
     * Builds the client context from the command line, environment and settings file, then connects (in local mode,
     * starting a child server when none is listening). Fields change only on success.
     */
    private void connect() throws Exception {
        ConfigurableApplicationContext next = null;
        var server = new LocalServer();
        try {
            next = new SpringApplicationBuilder(DesktopConfiguration.class).web(WebApplicationType.NONE)
                    .listeners((org.springframework.context.ApplicationListener<
                            org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>)
                            event -> {
                                DesktopSettings.load(event.getEnvironment());
                                LocalServer.provisionToken(event.getEnvironment());
                            })
                    .run(getParameters().getRaw().toArray(String[]::new));
            WorkbenchApi client = next.getBean(WorkbenchClient.class);
            server.connect(next.getEnvironment(), client);
            context = next;
            api = client;
            localServer = server;
            session = new ProjectSession(client);
        } catch (Exception e) {
            server.close();
            if (next != null) {
                next.close();
            }
            throw e;
        }
    }

    private void disconnect() {
        localServer.close();
        if (context != null) {
            context.close();
        }
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        schematic = new SchematicPane(this);
        simulation = new SimulationPane(this);
        pcb = new PcbPane(this);
        assembly = new AssemblyPane(this);
        tabs.getTabs().setAll(tab("Schematic", schematic.node()), tab("Simulation", simulation.node()),
                tab("PCB", pcb.node()), tab("3D", assembly.node()));
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, old, now) -> {
            pcb.setActive(now.intValue() == 2);
            assembly.setActive(now.intValue() == 3);
        });
        busy.setPrefSize(16, 16);
        busy.setVisible(false);
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        healthLabel.setTooltip(new Tooltip("Checking the server..."));
        healthLabel.setOnMouseClicked(e -> openSettings());
        healthLabel.setCursor(javafx.scene.Cursor.HAND);
        var statusBar = new HBox(8, busy, status, spacer, projectLabel, new Separator(javafx.geometry.Orientation.VERTICAL),
                healthLabel);
        statusBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(3, 8, 3, 8));
        root = new BorderPane(tabs);
        root.setTop(menuBar());
        root.setBottom(statusBar);
        var scene = new Scene(root, 1400, 900);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Shift+Z"), () -> step(false));
        stage.setScene(scene);
        updateHistory(null);
        stage.setMaximized(true);
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard("closing")) {
                e.consume();
            }
        });
        updateTitle();
        stage.show();
        health.start(this::serverUrl, h -> Platform.runLater(() -> showHealth(h)));
        if (startupFailure != null) {
            recoverStartup(startupFailure);
            return;
        }
        connected();
        String open = property("expanse.open");
        if (open != null && !open.isBlank()) {
            openFile(Path.of(open), true);
        }
        String capture = property("expanse.desktop.capture-dir");
        if (capture != null && !capture.isBlank()) {
            scheduleCapture(Path.of(capture));
        }
    }

    private String property(String name) {
        return context == null ? null : context.getEnvironment().getProperty(name);
    }

    private String serverUrl() {
        String url = property(DesktopSettings.SERVER_URL);
        if (url == null) {
            var settings = DesktopSettings.file(new StandardEnvironment());
            url = Files.isRegularFile(settings) ? DesktopSettings.read(settings).serverUrl() : DesktopSettings.DEFAULT_URL;
        }
        return url;
    }

    /** Runs once a server connection is established: loads the parts library into the palette. */
    private void connected() {
        run(() -> api.search(""), definitions -> {
            schematic.setLibrary(definitions);
            status("Library: " + definitions.size() + " parts");
        });
        health.refresh(this::serverUrl, h -> Platform.runLater(() -> showHealth(h)));
    }

    private void showHealth(HealthMonitor.Health result) {
        String mode = context == null ? "not connected" : property(DesktopSettings.MODE) == null ? "local"
                : property(DesktopSettings.MODE);
        healthDot.setFill(switch (result.status()) {
            case UP -> Color.web("#2e9d4c");
            case DEGRADED -> Color.web("#e0a100");
            case UNREACHABLE -> Color.web("#d03a2f");
        });
        healthLabel.setText(mode + " server");
        healthLabel.getTooltip().setText(serverUrl() + "\nHealth: " + result.detail() + " (checked "
                + java.time.LocalTime.now().withNano(0) + ", every " + HealthMonitor.INTERVAL.toSeconds()
                + " s)\nClick to change the server settings.");
    }

    private void recoverStartup(Exception failure) {
        var settings = new ButtonType("Settings...", ButtonBar.ButtonData.OK_DONE);
        var alert = new Alert(Alert.AlertType.ERROR, "Expanse Design could not connect to its server:\n\n"
                + message(failure) + "\n\nCheck the connection settings, or exit.", settings, ButtonType.CLOSE);
        alert.setHeaderText("No server connection");
        alert.initOwner(stage);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (alert.showAndWait().orElse(ButtonType.CLOSE) == settings) {
            openSettings();
        } else {
            Platform.exit();
        }
    }

    /** File > Settings: saves the settings file and optionally reconnects in place. */
    private void openSettings() {
        var environment = context == null ? new StandardEnvironment() : context.getEnvironment();
        Path file = DesktopSettings.file(environment);
        var shown = context != null ? DesktopSettings.effective(environment)
                : Files.isRegularFile(file) ? DesktopSettings.read(file) : new DesktopSettings.Values(null, null, null, null);
        var choice = SettingsDialog.show(stage, shown, file, health);
        if (choice.isEmpty()) {
            if (context == null) {
                Platform.exit();
            }
            return;
        }
        byte[] previous;
        try {
            previous = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            DesktopSettings.write(file, choice.get().values());
        } catch (IOException e) {
            error("Could not save the settings: " + e.getMessage());
            return;
        }
        if (!choice.get().reconnect() && context != null) {
            status("Settings saved to " + file + "; they apply at the next start or reconnect");
            return;
        }
        if (context != null && !confirmDiscard("reconnecting")) {
            return;
        }
        reconnect(file, previous);
    }

    /** Swaps the server connection; on failure restores the previous settings file and connection. */
    private void reconnect(Path file, byte[] previousSettings) {
        Path reopen = currentFile;
        boolean wasConnected = context != null;
        run(() -> {
            if (wasConnected) {
                disconnect();
            }
            try {
                connect();
                return null;
            } catch (Exception failure) {
                if (previousSettings != null) {
                    Files.write(file, previousSettings);
                } else {
                    Files.deleteIfExists(file);
                }
                if (wasConnected) {
                    try {
                        connect();
                    } catch (Exception again) {
                        failure.addSuppressed(again);
                        context = null;
                    }
                } else {
                    context = null;
                }
                return failure;
            }
        }, failure -> {
            if (failure != null) {
                health.refresh(this::serverUrl, h -> Platform.runLater(() -> showHealth(h)));
                String restored = context == null ? "There is no server connection; open File > Settings to try again."
                        : "The previous settings and connection were restored.";
                error("Could not connect with the new settings: " + message(failure) + "\n\n" + restored);
                if (context == null) {
                    recoverStartup(failure);
                }
                return;
            }
            startupFailure = null;
            current = null;
            publish(new State(null, null, false, null, null));
            connected();
            status("Connected to " + serverUrl());
            if (reopen != null && Files.isRegularFile(reopen)) {
                openFile(reopen, false);
            }
        });
    }
    private static Tab tab(String name, javafx.scene.Node content) {
        var tab = new Tab(name, content);
        tab.setClosable(false);
        return tab;
    }

    private MenuBar menuBar() {
        var newProject = item("New project...", "Shortcut+N", this::newProject);
        var open = item("Open file...", "Shortcut+O", this::openFileDialog);
        var openServer = item("Open from server...", "Shortcut+Shift+O", this::openFromServerDialog);
        var save = item("Save", "Shortcut+S", this::save);
        var saveAs = item("Save as...", "Shortcut+Shift+S", this::saveAs);
        var reload = item("Reload from server", "F5", () -> {
            if (current != null) {
                runState(() -> {
                    session.reload();
                    return state(null);
                });
            }
        });
        var close = item("Close project", null, () -> {
            if (current != null && confirmDiscard("closing the project")) {
                runState(() -> {
                    session.close();
                    return state(null);
                });
            }
        });
        var exit = item("Exit", null, () -> {
            if (confirmDiscard("exiting")) {
                Platform.exit();
            }
        });
        var settings = item("Settings...", "Shortcut+Comma", this::openSettings);
        var file = new Menu("File", null, newProject, open, openServer, new SeparatorMenuItem(), save, saveAs,
                new SeparatorMenuItem(), reload, close, new SeparatorMenuItem(), settings, new SeparatorMenuItem(), exit);

        var edit = new Menu("Edit", null, undoItem, redoItem);

        var rename = item("Rename project...", null, this::renameProject);
        var validate = item("Validate", "F7", () -> {
            tabs.getSelectionModel().select(0);
            schematic.validate();
        });
        var simulate = item("Run simulation", "F9", () -> {
            tabs.getSelectionModel().select(1);
            simulation.run();
        });
        var sync = item("Sync board from schematic", null, () -> apply(List.of(new SyncBoardOp())));
        var parts = item("Part editor...", "Shortcut+E", () -> {
            tabs.getSelectionModel().select(0);
            schematic.openPartEditor(null);
        });
        var design = new Menu("Design", null, rename, validate, simulate, sync, new SeparatorMenuItem(), parts);

        var about = item("About and MCP setup", null, this::about);
        return new MenuBar(file, edit, design, new Menu("Help", null, about));
    }

    private static MenuItem item(String text, String accelerator, Runnable action) {
        var item = new MenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    // ---- Workbench ----

    @Override
    public WorkbenchApi api() {
        return api;
    }

    @Override
    public Snapshot project() {
        return current;
    }

    @Override
    public void apply(List<Operation> operations) {
        if (current == null || operations.isEmpty()) {
            return;
        }
        List<Operation> copy = List.copyOf(operations);
        run(() -> {
            ChangeResult result = session.apply(copy);
            return state(result.diagnostics());
        }, this::publish, failure -> {
            if (failure instanceof ConnectorException e && "REVISION_CONFLICT".equals(e.error().code())) {
                run(() -> state(null), this::publish);
                error("The project changed on the server (another client or an AI agent edited it). "
                        + "The latest revision has been loaded; please repeat the edit.");
            } else {
                error(message(failure));
            }
        });
    }

    @Override
    public <T> void run(Callable<T> call, Consumer<T> onSuccess) {
        run(call, onSuccess, failure -> error(message(failure)));
    }

    private <T> void run(Callable<T> call, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return call.call();
            }
        };
        pending++;
        tabs.setDisable(true);
        busy.setVisible(true);
        task.setOnSucceeded(e -> {
            finished();
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            finished();
            onFailure.accept(task.getException());
        });
        worker.submit(task);
    }

    private void finished() {
        pending--;
        tabs.setDisable(pending > 0);
        busy.setVisible(pending > 0);
    }

    private void runState(Callable<State> call) {
        run(call, this::publish);
    }

    @Override
    public void select(UUID componentId) {
        selected = componentId;
        schematic.selectionChanged();
        pcb.selectionChanged();
    }

    @Override
    public UUID selected() {
        return selected;
    }

    @Override
    public void status(String message) {
        status.setText(message);
    }

    @Override
    public void error(String message) {
        var alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText("The operation did not complete");
        alert.initOwner(stage);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.showAndWait();
    }

    private static String message(Throwable failure) {
        if (failure instanceof ConnectorException e && e.error() != null) {
            return e.error().message() + " (" + e.error().code() + ")";
        }
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    // ---- Project lifecycle ----

    /** Must run on the worker thread. */
    private State state(List<Diagnostic> diagnostics) {
        HistoryView history = null;
        try {
            history = session.history();
        } catch (RuntimeException e) {
            // Undo stays disabled when the server cannot report history (for example an older server).
        }
        return new State(session.snapshot(), session.file(), session.dirty(), diagnostics, history);
    }

    /** Undo or redo the latest edit of the open project, whoever made it (this app, another client or MCP). */
    private void step(boolean undo) {
        if (current == null || undoItem.isDisable() && undo || redoItem.isDisable() && !undo) {
            return;
        }
        run(() -> {
            ChangeResult result = undo ? session.undo() : session.redo();
            return java.util.Map.entry(result.changes().getFirst(), state(result.diagnostics()));
        }, done -> {
            publish(done.getValue());
            status(done.getKey());
        }, failure -> {
            run(() -> state(null), this::publish);
            if (!(failure instanceof ConnectorException e && "REVISION_CONFLICT".equals(e.error().code()))) {
                error(message(failure));
            } else {
                status("The project changed on the server; the latest revision has been loaded");
            }
        });
    }

    private void updateHistory(HistoryView history) {
        undoItem.setDisable(history == null || !history.canUndo());
        redoItem.setDisable(history == null || !history.canRedo());
        undoItem.setText(history != null && history.canUndo() ? "Undo (restore revision " + history.undoRevision()
                + ")" : "Undo");
        redoItem.setText(history != null && history.canRedo() ? "Redo (restore revision " + history.redoRevision()
                + ")" : "Redo");
    }

    private void publish(State state) {
        boolean newProject = state.snapshot() == null || current == null || !current.id().equals(state.snapshot().id());
        current = state.snapshot();
        currentFile = state.file();
        updateHistory(state.history());
        dirty = state.dirty();
        if (newProject) {
            selected = null;
        } else if (selected != null && current.components().stream().noneMatch(c -> c.id().equals(selected))) {
            selected = null;
        }
        schematic.show(current, state.diagnostics());
        simulation.show(current);
        pcb.show(current);
        assembly.show(current);
        updateTitle();
    }

    private void updateTitle() {
        String name = current == null ? "No project" : current.name();
        stage.setTitle((dirty ? "*" : "") + name + " - Expanse Design");
        projectLabel.setText(current == null ? "" : "revision " + current.revision() + " \u2022 "
                + (currentFile == null ? "not saved to a file" : currentFile.toString()));
    }

    /** Returns true when it is fine to drop the current project's unsaved file state. */
    private boolean confirmDiscard(String action) {
        if (current == null || !dirty) {
            return true;
        }
        var saveButton = new ButtonType("Save", ButtonBar.ButtonData.YES);
        var discard = new ButtonType("Don't save", ButtonBar.ButtonData.NO);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, "\"" + current.name() + "\" has changes that are not "
                + "saved to a file. The server keeps every revision, but the project file will be out of date.",
                saveButton, discard, ButtonType.CANCEL);
        alert.setHeaderText("Save changes before " + action + "?");
        alert.initOwner(stage);
        var choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == discard) {
            return true;
        }
        if (choice != saveButton) {
            return false;
        }
        Path target = currentFile != null ? currentFile : chooseSaveFile();
        if (target == null) {
            return false;
        }
        try {
            // saving synchronously keeps the close/exit flow simple; the worker is idle while a modal dialog is up
            worker.submit(() -> {
                session.saveAs(target);
                return null;
            }).get();
            dirty = false;
            return true;
        } catch (Exception e) {
            error("Could not save: " + message(e.getCause() == null ? e : e.getCause()));
            return false;
        }
    }

    private void newProject() {
        if (!confirmDiscard("creating a new project")) {
            return;
        }
        var dialog = new TextInputDialog("Untitled circuit");
        dialog.setHeaderText("Name of the new project");
        dialog.initOwner(stage);
        dialog.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> runState(() -> {
            session.create(name);
            return state(null);
        }));
    }

    private FileChooser projectChooser() {
        var chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Expanse project (*.json)", "*.json"));
        if (currentFile != null && currentFile.getParent() != null) {
            chooser.setInitialDirectory(currentFile.getParent().toFile());
        }
        return chooser;
    }

    private void openFileDialog() {
        if (!confirmDiscard("opening another project")) {
            return;
        }
        File file = projectChooser().showOpenDialog(stage);
        if (file != null) {
            openFile(file.toPath(), false);
        }
    }

    private void openFile(Path path, boolean unattended) {
        run(() -> session.inspect(path), plan -> {
            if (plan.existing().isEmpty()) {
                openPlan(plan, ProjectSession.OpenChoice.REPLACE);
                return;
            }
            if (unattended) {
                openPlan(plan, ProjectSession.OpenChoice.REPLACE);
                return;
            }
            Snapshot existing = plan.existing().get();
            var replace = new ButtonType("Use the file", ButtonBar.ButtonData.YES);
            var copy = new ButtonType("Open as a copy", ButtonBar.ButtonData.OTHER);
            var server = new ButtonType("Use the server version", ButtonBar.ButtonData.NO);
            var alert = new Alert(Alert.AlertType.CONFIRMATION, "The server already has \"" + existing.name()
                    + "\" at revision " + existing.revision() + "; the file was saved at revision "
                    + plan.info().revision() + ".\n\n\u2022 Use the file: the server project is replaced by the file "
                    + "(as a new revision).\n\u2022 Open as a copy: the file is imported as a separate project.\n"
                    + "\u2022 Use the server version: the file is ignored until you save over it.",
                    replace, copy, server, ButtonType.CANCEL);
            alert.setHeaderText("This project is already on the server");
            alert.initOwner(stage);
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait().ifPresent(choice -> {
                if (choice == replace) {
                    openPlan(plan, ProjectSession.OpenChoice.REPLACE);
                } else if (choice == copy) {
                    openPlan(plan, ProjectSession.OpenChoice.COPY);
                } else if (choice == server) {
                    openPlan(plan, ProjectSession.OpenChoice.EXISTING);
                }
            });
        });
    }

    private void openPlan(ProjectSession.OpenPlan plan, ProjectSession.OpenChoice choice) {
        run(() -> {
            session.open(plan, choice);
            return state(null);
        }, state -> {
            publish(state);
            status("Opened " + plan.file().getFileName() + (choice == ProjectSession.OpenChoice.COPY ? " as a copy" : ""));
        });
    }

    private void openFromServerDialog() {
        if (!confirmDiscard("opening another project")) {
            return;
        }
        var list = new ListView<Summary>();
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Summary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + "   (revision " + item.revision() + ", "
                        + item.components() + " parts, " + item.nets() + " nets)");
            }
        });
        list.setPrefSize(520, 360);
        int[] offset = {0};
        var previous = new Button("Previous");
        var next = new Button("Next");
        var delete = new Button("Delete...");
        Runnable load = () -> run(() -> api.projects(offset[0], PAGE), items -> {
            list.getItems().setAll(items);
            previous.setDisable(offset[0] == 0);
            next.setDisable(items.size() < PAGE);
        });
        previous.setOnAction(e -> {
            offset[0] = Math.max(0, offset[0] - PAGE);
            load.run();
        });
        next.setOnAction(e -> {
            offset[0] += PAGE;
            load.run();
        });
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> {
            Summary item = list.getSelectionModel().getSelectedItem();
            var confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete \"" + item.name()
                    + "\" from the server? Files you saved are not affected.", ButtonType.YES, ButtonType.NO);
            confirm.initOwner(stage);
            if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                run(() -> {
                    api.delete(item.id(), item.revision());
                    if (current != null && current.id().equals(item.id())) {
                        session.close();
                    }
                    return state(null);
                }, state -> {
                    publish(state);
                    load.run();
                });
            }
        });
        var dialog = new Dialog<Summary>();
        dialog.setTitle("Open from server");
        dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(6, list, new HBox(6, previous, next, delete)));
        var openButton = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(openButton, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(openButton).disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(list.getSelectionModel().getSelectedItem());
                dialog.close();
            }
        });
        dialog.setResultConverter(b -> b == openButton ? list.getSelectionModel().getSelectedItem() : null);
        load.run();
        dialog.showAndWait().ifPresent(summary -> runState(() -> {
            session.openFromServer(summary.id());
            return state(null);
        }));
    }

    private void save() {
        if (current == null) {
            return;
        }
        if (currentFile == null) {
            saveAs();
            return;
        }
        run(() -> {
            session.save();
            return state(null);
        }, state -> {
            publish(state);
            status("Saved " + state.file());
        });
    }

    private Path chooseSaveFile() {
        var chooser = projectChooser();
        chooser.setInitialFileName((currentFile != null ? currentFile.getFileName().toString()
                : current.name().replaceAll("[^A-Za-z0-9._ -]", "_") + ".json"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return null;
        }
        Path path = file.toPath();
        return path.getFileName().toString().contains(".") ? path : path.resolveSibling(path.getFileName() + ".json");
    }

    private void saveAs() {
        if (current == null) {
            return;
        }
        Path target = chooseSaveFile();
        if (target != null) {
            run(() -> {
                session.saveAs(target);
                return state(null);
            }, state -> {
                publish(state);
                status("Saved " + state.file() + " (the previous file, if any, is kept as .bak)");
            });
        }
    }

    private void renameProject() {
        if (current == null) {
            return;
        }
        var dialog = new TextInputDialog(current.name());
        dialog.setHeaderText("New project name");
        dialog.initOwner(stage);
        dialog.showAndWait().map(String::strip).filter(n -> !n.isEmpty() && !n.equals(current.name()))
                .ifPresent(name -> apply(List.of(new RenameProjectOp(name))));
    }

    private void about() {
        var environment = context == null ? new StandardEnvironment() : context.getEnvironment();
        String server = serverUrl();
        var text = new TextArea("""
                Expanse Design %s

                Server: %s
                Each edit is stored as a revision on the server; File > Save writes a portable JSON document.

                AI control (MCP): run the MCP bridge over stdio from your agent's MCP configuration:

                  command: java -jar expanse-mcp-<version>.jar
                  env:     EXPANSE_SERVER_URL=%s
                           EXPANSE_API_TOKEN=<owner token to edit, reader token for read-only>

                Without EXPANSE_API_TOKEN the desktop generates a local token in %s.

                Options: --expanse.mcp.allow-mutations=true|false, --expanse.mcp.allow-destructive=true|false.
                Agents and this window edit the same project; if both edit at once, the later edit is rejected
                with a revision conflict and the window reloads the latest revision (File > Reload, F5).
                """.formatted(getClass().getPackage().getImplementationVersion() == null ? "(development)"
                : getClass().getPackage().getImplementationVersion(), server, server,
                LocalServer.dataDirectory(environment).resolve(LocalServer.TOKEN_FILE)));
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(640, 320);
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("About Expanse Design");
        alert.getDialogPane().setContent(text);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    // ---- Visual verification: -Dexpanse.desktop.capture-dir=<dir> saves a PNG of each tab, then exits.
    // Optional -Dexpanse.desktop.capture-simulation="logic;3m;SW1 1.2m closed" presets the simulation run. ----

    private void scheduleCapture(Path directory) {
        var delay = new PauseTransition(Duration.seconds(4));
        delay.setOnFinished(e -> captureTab(directory, 0));
        delay.play();
    }

    private void captureTab(Path directory, int index) {
        if (pending > 0) {
            var wait = new PauseTransition(Duration.millis(500));
            wait.setOnFinished(e -> captureTab(directory, index));
            wait.play();
            return;
        }
        if (index >= tabs.getTabs().size()) {
            Platform.exit();
            return;
        }
        tabs.getSelectionModel().select(index);
        if (index == 1 && current != null) {
            String preset = property("expanse.desktop.capture-simulation");
            if (preset != null) {
                simulation.configure(preset);
            }
            simulation.run();
        }
        var settle = new PauseTransition(Duration.seconds(2));
        settle.setOnFinished(e -> {
            if (pending > 0) {
                captureTab(directory, index);
                return;
            }
            try {
                Files.createDirectories(directory);
                WritableImage image = root.snapshot(null, null);
                write(image, directory.resolve((index + 1) + "-" + tabs.getTabs().get(index).getText().toLowerCase()
                        + ".png"));
            } catch (IOException ex) {
                System.err.println("Capture failed: " + ex.getMessage());
            }
            captureTab(directory, index + 1);
        });
        settle.play();
    }

    private static void write(WritableImage image, Path target) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        var buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", target.toFile());
    }

    @Override
    public void stop() {
        worker.shutdownNow();
        health.close();
        disconnect();
    }
}
