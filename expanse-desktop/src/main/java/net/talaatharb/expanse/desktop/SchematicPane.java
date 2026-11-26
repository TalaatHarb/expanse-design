package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import net.talaatharb.expanse.dto.ApiModels.*;

/** Schematic editor: palette, canvas with place/move/rotate/wire tools, property sheet and diagnostics. */
final class SchematicPane {
    private enum Tool { SELECT, PLACE, WIRE, LABEL }

    /** A drawn net label in screen coordinates, kept from the last redraw for hit testing. */
    private record LabelBox(NetLabelView label, NetView net, double x, double y, double width, double height) {
        boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    private record PinHit(ComponentView component, PinView pin, double x, double y) {
        String label() {
            return component.reference() + "." + pin.number();
        }
    }

    private record WireHit(NetView net, WireView wire, double x, double y) {
    }

    /** A wire end: either a pin or a point on an existing wire of a net (a tee). */
    private record Anchor(UUID pinId, UUID netId, double x, double y, String label) {
        static Anchor of(PinHit pin) {
            return new Anchor(pin.pin().id(), null, pin.x(), pin.y(), pin.label());
        }

        static Anchor of(WireHit hit) {
            return new Anchor(null, hit.net().id(), hit.x(), hit.y(), hit.net().name());
        }
    }

    private static final Color WIRE = Color.web("#1b7f3b");
    private static final Color INK = Color.web("#263238");
    private static final Color SELECTED = Color.web("#1565c0");
    private final Workbench workbench;
    private final BorderPane root = new BorderPane();
    private final Canvas canvas = new Canvas(800, 600);
    private final ListView<DefinitionView> palette = new ListView<>();
    private final VBox properties = new VBox(6);
    private final ListView<Diagnostic> diagnostics = new ListView<>();
    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ToggleButton selectTool = new ToggleButton("Select");
    private final ToggleButton placeTool = new ToggleButton("Place");
    private final ToggleButton wireTool = new ToggleButton("Wire");
    private final ToggleButton labelTool = new ToggleButton("Label");
    private final Label hint = new Label();
    /** Selected components in selection order; the first is the workbench (primary) selection. */
    private final LinkedHashSet<UUID> selection = new LinkedHashSet<>();
    private final List<LabelBox> labelBoxes = new ArrayList<>();
    private UUID selectedLabel;
    private boolean rubberBand;
    private boolean spaceDown;
    private boolean mouseInside;
    private SchematicClipboard.Clip clip;
    private int pasteCount;
    private List<String> pendingSelection;
    private Snapshot project;
    private List<DefinitionView> library = List.of();
    private Map<UUID, NetView> netByPin = Map.of();
    private double scale = 7;
    private double offsetX = 80;
    private double offsetY = 80;
    private Tool tool = Tool.SELECT;
    private Anchor wireStart;
    private final List<double[]> bends = new ArrayList<>();
    private UUID selectedWire;
    private double mouseX;
    private double mouseY;
    private ComponentView dragging;
    private double dragDx;
    private double dragDy;
    private double pressX;
    private double pressY;
    private boolean dragMoved;
    private boolean panning;
    private ContextMenu menu;
    private UUID fittedProject;

    SchematicPane(Workbench workbench) {
        this.workbench = workbench;
        selectTool.setToggleGroup(toolGroup);
        placeTool.setToggleGroup(toolGroup);
        wireTool.setToggleGroup(toolGroup);
        labelTool.setToggleGroup(toolGroup);
        selectTool.setSelected(true);
        selectTool.setTooltip(new Tooltip("Select (S): click or drag a box to select, Shift/Ctrl-click to add or "
                + "remove, drag to move; pan with the middle button or Space+drag"));
        labelTool.setTooltip(new Tooltip("Net labels (L): click a pin or wire and type a net name; pins with the same "
                + "label are connected without a wire"));
        placeTool.setTooltip(new Tooltip("Click to place the part selected in the palette (P)"));
        wireTool.setTooltip(new Tooltip("Draw wires (W): click a pin or wire, click empty space to add bends, click a "
                + "pin or wire to finish. Pins can also be wired in Select mode."));
        toolGroup.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            tool = now == placeTool ? Tool.PLACE : now == wireTool ? Tool.WIRE : now == labelTool ? Tool.LABEL
                    : Tool.SELECT;
            cancelWire();
            updateHint();
            redraw();
        });
        Button rotate = new Button("Rotate");
        rotate.setOnAction(e -> rotateSelected());
        Button delete = new Button("Delete");
        delete.setOnAction(e -> deleteSelected());
        Button fit = new Button("Fit");
        fit.setOnAction(e -> {
            fit();
            redraw();
        });
        Button validate = new Button("Check (ERC)");
        validate.setOnAction(e -> validate());
        selectTool.setId("schematic-select");
        placeTool.setId("schematic-place");
        wireTool.setId("schematic-wire");
        labelTool.setId("schematic-label");
        canvas.setId("schematic-canvas");
        palette.setId("schematic-palette");
        properties.setId("schematic-properties");
        var toolbar = new HBox(6, selectTool, placeTool, wireTool, labelTool, new Separator(Orientation.VERTICAL),
                rotate, delete,
                fit, validate, new Separator(Orientation.VERTICAL), hint);
        toolbar.setPadding(new Insets(4));

        palette.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DefinitionView item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
                setTooltip(empty || item == null ? null : new Tooltip(item.id() + " v" + item.version()
                        + (item.pcbReady() ? "" : " (schematic only, no footprint)")));
            }
        });
        palette.getSelectionModel().selectedItemProperty().addListener((o, old, now) -> {
            if (now != null) {
                placeTool.setSelected(true);
            }
        });
        palette.setOnMouseClicked(e -> {
            if (palette.getSelectionModel().getSelectedItem() != null) {
                placeTool.setSelected(true);
            }
        });
        palette.setPrefWidth(190);
        var newPart = new Button("Part editor...");
        newPart.setTooltip(new Tooltip("Create or edit project parts (custom symbols, footprints and 3D bodies)"));
        newPart.setMaxWidth(Double.MAX_VALUE);
        newPart.setOnAction(e -> openPartEditor(null));
        var left = new VBox(4, new Label("Parts"), palette, newPart);
        VBox.setVgrow(palette, Priority.ALWAYS);
        left.setPadding(new Insets(4));

        var holder = new Pane(canvas);
        holder.setMinSize(200, 200);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener(o -> redraw());
        canvas.heightProperty().addListener(o -> redraw());
        canvas.setFocusTraversable(true);
        canvas.setOnMousePressed(this::pressed);
        canvas.setOnMouseDragged(this::dragged);
        canvas.setOnMouseReleased(this::released);
        canvas.setOnMouseMoved(e -> {
            mouseX = e.getX();
            mouseY = e.getY();
            if (wireStart != null || tool == Tool.PLACE) {
                redraw();
            }
        });
        canvas.setOnMouseEntered(e -> mouseInside = true);
        canvas.setOnMouseExited(e -> mouseInside = false);
        canvas.setOnScroll(this::zoom);
        canvas.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spaceDown = false;
            }
        });
        canvas.setOnKeyPressed(e -> {
            if (e.isShortcutDown() || e.isAltDown()) {
                return;
            }
            switch (e.getCode()) {
                case SPACE -> spaceDown = true;
                case R -> rotateSelected();
                case DELETE -> deleteSelected();
                case BACK_SPACE -> {
                    if (wireStart != null) {
                        if (!bends.isEmpty()) {
                            bends.removeLast();
                        }
                        redraw();
                    } else {
                        deleteSelected();
                    }
                }
                case ESCAPE -> {
                    if (wireStart != null) {
                        cancelWire();
                    } else if (tool != Tool.SELECT) {
                        selectTool.setSelected(true);
                    } else {
                        clearSelection();
                    }
                    redraw();
                }
                case S -> selectTool.setSelected(true);
                case P -> placeTool.setSelected(true);
                case W -> wireTool.setSelected(true);
                case L -> labelTool.setSelected(true);
                case F -> {
                    fit();
                    redraw();
                }
                default -> {
                    return;
                }
            }
            e.consume();
        });

        properties.setPadding(new Insets(6));
        properties.setPrefWidth(260);
        var propertiesScroll = new ScrollPane(properties);
        propertiesScroll.setFitToWidth(true);
        diagnostics.setPrefHeight(110);
        diagnostics.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.severity() + "  " + item.code() + "  " + item.message());
                setTextFill(item != null && item.severity().equals("ERROR") ? Color.FIREBRICK : Color.BLACK);
            }
        });
        diagnostics.getSelectionModel().selectedItemProperty().addListener((o, old, now) -> {
            if (now != null && !now.affectedIds().isEmpty() && project != null) {
                UUID id = now.affectedIds().getFirst();
                if (project.components().stream().anyMatch(c -> c.id().equals(id))) {
                    workbench.select(id);
                }
            }
        });
        var center = new SplitPane(holder, diagnostics);
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.84);
        root.setTop(toolbar);
        root.setLeft(left);
        root.setCenter(center);
        root.setRight(propertiesScroll);
        updateHint();
        showProperties();
    }

    Node node() {
        return root;
    }

    void setLibrary(List<DefinitionView> definitions) {
        library = List.copyOf(definitions);
        refreshPalette();
    }

    /** Project parts first, then the built-in library; the selection survives refreshes by ID. */
    private void refreshPalette() {
        var items = new ArrayList<DefinitionView>();
        if (project != null) {
            project.parts().forEach(part -> items.add(PartForms.definition(part)));
        }
        items.addAll(library);
        if (items.equals(palette.getItems())) {
            return;
        }
        DefinitionView selected = palette.getSelectionModel().getSelectedItem();
        boolean placing = tool == Tool.PLACE;
        palette.getItems().setAll(items);
        if (selected != null) {
            items.stream().filter(d -> d.id().equals(selected.id())).findFirst()
                    .ifPresent(d -> palette.getSelectionModel().select(d));
        }
        if (!placing && tool == Tool.PLACE) {
            selectTool.setSelected(true);
        }
    }

    /** Opens the part editor, optionally on an existing project part, and applies the result. */
    void openPartEditor(String partId) {
        if (project == null) {
            workbench.status("Open or create a project first; custom parts are stored in the project");
            return;
        }
        PartEditor.show(canvas.getScene().getWindow(), project.parts(), library, partId).ifPresent(operation -> {
            workbench.apply(List.of(operation));
            workbench.status(operation instanceof DefinePartOp define ? "Saved part " + define.part().name()
                    + "; it is at the top of the palette" : "Deleted part " + ((DeletePartOp) operation).partId());
        });
    }

    void show(Snapshot snapshot, List<Diagnostic> latestDiagnostics) {
        project = snapshot;
        refreshPalette();
        var index = new HashMap<UUID, NetView>();
        if (snapshot != null) {
            snapshot.nets().forEach(net -> net.pinIds().forEach(pin -> index.put(pin, net)));
        }
        netByPin = index;
        dragging = null;
        if (wireStart != null && (snapshot == null || (wireStart.pinId() != null
                ? snapshot.components().stream().flatMap(c -> c.pins().stream()).noneMatch(p -> p.id().equals(wireStart.pinId()))
                : snapshot.nets().stream().noneMatch(n -> n.id().equals(wireStart.netId()))))) {
            cancelWire();
        }
        if (selectedWire != null && (snapshot == null || snapshot.nets().stream()
                .flatMap(n -> n.wires().stream()).noneMatch(w -> w.id().equals(selectedWire)))) {
            selectedWire = null;
        }
        if (selectedLabel != null && (snapshot == null || snapshot.nets().stream()
                .flatMap(n -> n.labels().stream()).noneMatch(l -> l.id().equals(selectedLabel)))) {
            selectedLabel = null;
        }
        var existing = new HashSet<UUID>();
        if (snapshot != null) {
            snapshot.components().forEach(c -> existing.add(c.id()));
        }
        selection.retainAll(existing);
        if (snapshot != null && !snapshot.id().equals(fittedProject)) {
            fittedProject = snapshot.id();
            selection.clear();
            fit();
        }
        if (latestDiagnostics != null) {
            diagnostics.getItems().setAll(latestDiagnostics);
        } else if (snapshot == null) {
            diagnostics.getItems().clear();
        }
        showProperties();
        redraw();
        if (pendingSelection != null && snapshot != null) {
            var references = new HashSet<>(pendingSelection);
            pendingSelection = null;
            setSelection(snapshot.components().stream().filter(c -> references.contains(c.reference()))
                    .map(ComponentView::id).toList());
        }
    }

    void selectionChanged() {
        UUID primary = workbench.selected();
        if (primary == null) {
            selection.clear();
        } else {
            selectedWire = null;
            selectedLabel = null;
            if (!selection.contains(primary)) {
                selection.clear();
                selection.add(primary);
            }
        }
        showProperties();
        redraw();
    }

    /** Replaces the component selection; the first ID becomes the workbench selection shared with the PCB view. */
    private void setSelection(Collection<UUID> ids) {
        selection.clear();
        selection.addAll(ids);
        if (!selection.isEmpty()) {
            selectedWire = null;
            selectedLabel = null;
        }
        UUID primary = selection.isEmpty() ? null : selection.iterator().next();
        if (Objects.equals(primary, workbench.selected())) {
            showProperties();
            redraw();
        } else {
            workbench.select(primary);
        }
    }

    private void clearSelection() {
        selectedWire = null;
        selectedLabel = null;
        setSelection(List.of());
    }

    /** Selected components in selection order. */
    List<ComponentView> selectedComponents() {
        if (project == null) {
            return List.of();
        }
        var byId = new HashMap<UUID, ComponentView>();
        project.components().forEach(c -> byId.put(c.id(), c));
        return selection.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    void selectAll() {
        if (project != null) {
            setSelection(project.components().stream().map(ComponentView::id).toList());
            workbench.status(selection.size() + " component(s) selected");
        }
    }

    // ---- Clipboard ----

    /** Copies the selected components with their internal connections; false when nothing is selected. */
    boolean copy() {
        var copied = project == null ? null : SchematicClipboard.copy(project, selection);
        if (copied == null) {
            workbench.status("Select components to copy");
            return false;
        }
        clip = copied;
        pasteCount = 0;
        workbench.status("Copied " + copied.components().size() + " component(s)");
        return true;
    }

    void cut() {
        if (copy()) {
            deleteComponents(selectedComponents());
        }
    }

    /** Pastes at the mouse when it is over the canvas, otherwise offset from the copied position. */
    void paste() {
        if (project == null || clip == null) {
            workbench.status(project == null ? "Open a project first" : "Nothing to paste; copy components first");
            return;
        }
        double dx;
        double dy;
        if (mouseInside) {
            dx = snap(mmX(mouseX)) - clip.originX();
            dy = snap(mmY(mouseY)) - clip.originY();
        } else {
            pasteCount++;
            dx = dy = 4 * Symbols.GRID * pasteCount;
        }
        var paste = SchematicClipboard.paste(clip, project, dx, dy);
        pendingSelection = paste.references();
        workbench.apply(paste.operations());
        workbench.status("Pasted " + String.join(", ", paste.references()));
    }

    void duplicate() {
        if (copy()) {
            boolean inside = mouseInside;
            mouseInside = false;
            paste();
            mouseInside = inside;
        }
    }

    void validate() {
        if (project != null) {
            UUID id = project.id();
            workbench.run(() -> workbench.api().validate(id), report -> {
                diagnostics.getItems().setAll(report.diagnostics());
                workbench.status("ERC on revision " + report.revision() + ": " + report.diagnostics().size()
                        + " finding(s)");
            });
        }
    }

    // ---- Coordinates ----

    private double sx(double xMm) {
        return offsetX + xMm * scale;
    }

    private double sy(double yMm) {
        return offsetY + yMm * scale;
    }

    private double mmX(double sx) {
        return (sx - offsetX) / scale;
    }

    private double mmY(double sy) {
        return (sy - offsetY) / scale;
    }

    /** Screen position of a schematic point; used by the UI tests to click pins and parts. */
    javafx.geometry.Point2D toScreen(double xMm, double yMm) {
        return canvas.localToScreen(sx(xMm), sy(yMm));
    }

    private static double snap(double mm) {
        return Math.round(mm / Symbols.GRID) * Symbols.GRID;
    }

    private void fit() {
        if (project == null || project.components().isEmpty()) {
            scale = 7;
            offsetX = 80;
            offsetY = 80;
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (ComponentView c : project.components()) {
            double[] h = Symbols.halfExtents(c);
            minX = Math.min(minX, c.xMm() - h[0]);
            maxX = Math.max(maxX, c.xMm() + h[0]);
            minY = Math.min(minY, c.yMm() - h[1]);
            maxY = Math.max(maxY, c.yMm() + h[1]);
        }
        double width = Math.max(canvas.getWidth(), 300);
        double height = Math.max(canvas.getHeight(), 300);
        scale = Math.max(2, Math.min(20, Math.min((width - 160) / Math.max(10, maxX - minX),
                (height - 120) / Math.max(10, maxY - minY))));
        offsetX = width / 2 - (minX + maxX) / 2 * scale;
        offsetY = height / 2 - (minY + maxY) / 2 * scale;
    }

    // ---- Hit testing ----

    private ComponentView selectedComponent() {
        UUID id = workbench.selected();
        return project == null || id == null ? null
                : project.components().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    private PinHit pinAt(double x, double y) {
        if (project == null) {
            return null;
        }
        double radius = Math.max(1.0, 6 / scale);
        PinHit best = null;
        double bestDistance = radius;
        for (ComponentView c : project.components()) {
            for (PinView p : c.pins()) {
                if (Symbols.hidden(c.kind(), p.number())) {
                    continue;
                }
                double[] at = Symbols.pin(c, p);
                double distance = Math.hypot(at[0] - mmX(x), at[1] - mmY(y));
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = new PinHit(c, p, at[0], at[1]);
                }
            }
        }
        return best;
    }

    private ComponentView componentAt(double x, double y) {
        if (project == null) {
            return null;
        }
        for (int i = project.components().size() - 1; i >= 0; i--) {
            ComponentView c = project.components().get(i);
            double[] h = Symbols.halfExtents(c);
            if (Math.abs(mmX(x) - c.xMm()) <= h[0] && Math.abs(mmY(y) - c.yMm()) <= h[1]) {
                return c;
            }
        }
        return null;
    }

    private Map<UUID, double[]> pinPositions() {
        var positions = new HashMap<UUID, double[]>();
        for (ComponentView c : project.components()) {
            for (PinView p : c.pins()) {
                positions.put(p.id(), Symbols.pin(c, p));
            }
        }
        return positions;
    }

    /** Closest drawn wire within a few pixels; the hit point is snapped to the grid when it stays on the wire. */
    private WireHit wireAt(double x, double y) {
        if (project == null) {
            return null;
        }
        var positions = pinPositions();
        double radius = Math.max(0.8, 5 / scale);
        WireHit best = null;
        double bestDistance = radius;
        for (NetView net : project.nets()) {
            for (WireView wire : net.wires()) {
                var path = WireGeometry.path(wire, positions);
                if (path == null) {
                    continue;
                }
                double[] at = WireGeometry.closest(path, mmX(x), mmY(y));
                double distance = Math.hypot(at[0] - mmX(x), at[1] - mmY(y));
                if (distance <= bestDistance) {
                    double[] snapped = {snap(at[0]), snap(at[1])};
                    if (WireGeometry.distance(path, snapped[0], snapped[1]) < WireGeometry.EPSILON) {
                        at = snapped;
                    }
                    bestDistance = distance;
                    best = new WireHit(net, wire, at[0], at[1]);
                }
            }
        }
        return best;
    }

    // ---- Mouse ----

    private void pressed(MouseEvent e) {
        canvas.requestFocus();
        if (menu != null) {
            menu.hide();
        }
        if (project == null) {
            return;
        }
        pressX = e.getX();
        pressY = e.getY();
        mouseX = e.getX();
        mouseY = e.getY();
        dragMoved = false;
        PinHit pin = pinAt(e.getX(), e.getY());
        LabelBox label = pin == null ? labelAt(e.getX(), e.getY()) : null;
        ComponentView component = componentAt(e.getX(), e.getY());
        WireHit wire = pin == null ? wireAt(e.getX(), e.getY()) : null;
        if (e.getButton() == MouseButton.SECONDARY) {
            contextMenu(e, pin, label, component, wire);
            return;
        }
        if (e.getButton() == MouseButton.MIDDLE || spaceDown) {
            panning = true;
            return;
        }
        if (tool == Tool.LABEL) {
            if (pin != null) {
                labelPin(pin);
            } else if (wire != null) {
                labelWire(wire);
            } else {
                workbench.status("Click a pin or a wire to label its net");
            }
            return;
        }
        if (tool == Tool.PLACE) {
            DefinitionView definition = palette.getSelectionModel().getSelectedItem();
            if (definition == null) {
                workbench.status("Choose a part in the palette first");
                return;
            }
            String reference = nextReference(definition.kind());
            workbench.apply(List.of(new PlaceOp(definition.id(), reference, snap(mmX(e.getX())), snap(mmY(e.getY())),
                    0, null, null)));
            workbench.status("Placed " + reference + (e.isShiftDown() ? "" : "; Shift-click to keep placing"));
            if (!e.isShiftDown()) {
                selectTool.setSelected(true);
            }
            return;
        }
        if (wireStart != null || pin != null || (tool == Tool.WIRE && wire != null)) {
            wireClick(e, pin, wire);
            return;
        }
        if (tool == Tool.WIRE) {
            workbench.status("Start a wire on a pin or on an existing wire");
            return;
        }
        if (label != null) {
            setSelection(List.of());
            selectedWire = null;
            selectedLabel = label.label().id();
            showProperties();
            redraw();
            return;
        }
        boolean toggle = e.isShiftDown() || e.isShortcutDown();
        if (component != null) {
            selectedWire = null;
            selectedLabel = null;
            if (toggle) {
                var next = new ArrayList<>(selection);
                if (!next.remove(component.id())) {
                    next.add(component.id());
                }
                setSelection(next);
                return;
            }
            if (!selection.contains(component.id())) {
                setSelection(List.of(component.id()));
            }
            dragging = component;
            dragDx = mmX(e.getX()) - component.xMm();
            dragDy = mmY(e.getY()) - component.yMm();
        } else if (wire != null) {
            setSelection(List.of());
            selectedLabel = null;
            selectedWire = wire.wire().id();
            showProperties();
            redraw();
        } else {
            selectedWire = null;
            selectedLabel = null;
            if (!toggle) {
                setSelection(List.of());
            }
            rubberBand = true;
        }
    }

    private void labelPin(PinHit pin) {
        NetView net = netByPin.get(pin.pin().id());
        askNetName("Label " + pin.label(), net == null ? "" : net.name()).ifPresent(name -> {
            workbench.apply(List.of(new PlaceNetLabelOp(name, pin.pin().id().toString(), null, null, null)));
            workbench.status("Labelled " + pin.label() + " as " + name);
        });
    }

    private void labelWire(WireHit wire) {
        askNetName("Label net " + wire.net().name(), wire.net().name()).ifPresent(name -> {
            workbench.apply(List.of(new PlaceNetLabelOp(name, null, wire.net().id().toString(), wire.x(), wire.y())));
            workbench.status("Labelled net " + wire.net().name() + " as " + name);
        });
    }

    private java.util.Optional<String> askNetName(String header, String initial) {
        var dialog = new TextInputDialog(initial);
        dialog.setTitle("Net label");
        dialog.setHeaderText(header + "\nPins with the same label share one net.");
        dialog.setContentText("Net name:");
        dialog.getEditor().setId("net-label-name");
        dialog.initOwner(canvas.getScene().getWindow());
        return dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty());
    }

    private LabelBox labelAt(double x, double y) {
        for (int i = labelBoxes.size() - 1; i >= 0; i--) {
            if (labelBoxes.get(i).contains(x, y)) {
                return labelBoxes.get(i);
            }
        }
        return null;
    }

    private void wireClick(MouseEvent e, PinHit pin, WireHit wire) {
        if (wireStart == null) {
            wireStart = pin != null ? Anchor.of(pin) : Anchor.of(wire);
            bends.clear();
            workbench.status("Wiring from " + wireStart.label() + "; click empty space for bends, a pin or wire to "
                    + "finish, Backspace removes a bend, Esc cancels");
        } else if (pin != null) {
            finishWire(Anchor.of(pin));
        } else if (wire != null) {
            finishWire(Anchor.of(wire));
        } else {
            double[] bend = {snap(mmX(e.getX())), snap(mmY(e.getY()))};
            double[] last = bends.isEmpty() ? new double[] {wireStart.x(), wireStart.y()} : bends.getLast();
            if (!WireGeometry.same(last, bend)) {
                bends.add(bend);
            }
        }
        redraw();
    }

    private void finishWire(Anchor end) {
        Anchor start = wireStart;
        if (start.pinId() != null && start.pinId().equals(end.pinId())) {
            workbench.status("A wire cannot start and end on the same pin");
            return;
        }
        if (start.netId() != null && end.netId() != null && !start.netId().equals(end.netId())) {
            workbench.status("Two different nets can only be joined through a pin; finish this wire on a pin");
            return;
        }
        var points = new ArrayList<PointView>();
        if (start.pinId() == null) {
            points.add(new PointView(start.x(), start.y()));
        }
        bends.forEach(b -> points.add(new PointView(b[0], b[1])));
        if (end.pinId() == null) {
            points.add(new PointView(end.x(), end.y()));
        }
        if (start.pinId() == null && end.pinId() == null
                && WireGeometry.same(new double[] {start.x(), start.y()}, new double[] {end.x(), end.y()})) {
            return;
        }
        UUID net = start.netId() != null ? start.netId() : end.netId();
        cancelWire();
        workbench.apply(List.of(new DrawWireOp(text(start.pinId()), text(end.pinId()), text(net), points, null)));
        workbench.status("Wired " + start.label() + " to " + end.label());
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private void cancelWire() {
        wireStart = null;
        bends.clear();
    }

    private void dragged(MouseEvent e) {
        if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
            dragMoved = true;
        }
        if (panning) {
            offsetX += e.getX() - mouseX;
            offsetY += e.getY() - mouseY;
        }
        mouseX = e.getX();
        mouseY = e.getY();
        redraw();
    }

    private void released(MouseEvent e) {
        if (dragging != null && dragMoved) {
            double ddx = snap(mmX(e.getX()) - dragDx) - dragging.xMm();
            double ddy = snap(mmY(e.getY()) - dragDy) - dragging.yMm();
            if (ddx != 0 || ddy != 0) {
                var moves = new ArrayList<Operation>();
                for (ComponentView c : movingComponents()) {
                    moves.add(new MoveOp(c.id().toString(), c.xMm() + ddx, c.yMm() + ddy, null));
                }
                workbench.apply(moves);
            }
        }
        if (rubberBand && dragMoved && project != null) {
            double x0 = mmX(Math.min(pressX, e.getX()));
            double x1 = mmX(Math.max(pressX, e.getX()));
            double y0 = mmY(Math.min(pressY, e.getY()));
            double y1 = mmY(Math.max(pressY, e.getY()));
            var next = new ArrayList<>(selection);
            project.components().stream()
                    .filter(c -> c.xMm() >= x0 && c.xMm() <= x1 && c.yMm() >= y0 && c.yMm() <= y1)
                    .map(ComponentView::id).filter(id -> !next.contains(id)).forEach(next::add);
            setSelection(next);
            workbench.status(selection.size() + " component(s) selected");
        }
        dragging = null;
        panning = false;
        rubberBand = false;
        redraw();
    }

    /** The components a drag moves: the whole selection when the dragged part is in it. */
    private List<ComponentView> movingComponents() {
        if (dragging == null) {
            return List.of();
        }
        return selection.contains(dragging.id()) ? selectedComponents() : List.of(dragging);
    }

    private void zoom(ScrollEvent e) {
        double factor = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
        double next = Math.max(1.5, Math.min(40, scale * factor));
        double x = mmX(e.getX());
        double y = mmY(e.getY());
        scale = next;
        offsetX = e.getX() - x * scale;
        offsetY = e.getY() - y * scale;
        redraw();
    }

    private void contextMenu(MouseEvent e, PinHit pin, LabelBox label, ComponentView component, WireHit wire) {
        menu = new ContextMenu();
        if (pin != null) {
            NetView net = netByPin.get(pin.pin().id());
            var start = new MenuItem("Start wire at " + pin.label());
            start.setOnAction(a -> {
                wireStart = Anchor.of(pin);
                bends.clear();
                redraw();
            });
            var addLabel = new MenuItem("Add net label...");
            addLabel.setOnAction(a -> labelPin(pin));
            menu.getItems().addAll(start, addLabel);
            if (net != null) {
                var disconnect = new MenuItem("Disconnect " + pin.label() + " from " + net.name());
                disconnect.setOnAction(a -> workbench.apply(List.of(new DisconnectOp(pin.pin().id().toString()))));
                var rename = new MenuItem("Rename net " + net.name() + "...");
                rename.setOnAction(a -> renameNet(net));
                menu.getItems().addAll(disconnect, rename);
            }
        } else if (label != null) {
            setSelection(List.of());
            selectedWire = null;
            selectedLabel = label.label().id();
            var delete = new MenuItem("Delete label (Del)");
            delete.setOnAction(a -> deleteSelected());
            var rename = new MenuItem("Rename net " + label.net().name() + "...");
            rename.setOnAction(a -> renameNet(label.net()));
            menu.getItems().addAll(delete, rename);
            showProperties();
            redraw();
        } else if (component != null) {
            if (!selection.contains(component.id())) {
                setSelection(List.of(component.id()));
            }
            int count = selection.size();
            var rotate = new MenuItem("Rotate (R)");
            rotate.setOnAction(a -> rotateSelected());
            var delete = new MenuItem(count > 1 ? "Delete " + count + " components (Del)" : "Delete (Del)");
            delete.setOnAction(a -> deleteSelected());
            var copyItem = new MenuItem("Copy");
            copyItem.setOnAction(a -> copy());
            var duplicateItem = new MenuItem("Duplicate");
            duplicateItem.setOnAction(a -> duplicate());
            menu.getItems().addAll(rotate, delete, copyItem, duplicateItem);
            if (component.definitionId().startsWith("project.")) {
                var edit = new MenuItem("Edit part " + component.definitionId() + "...");
                edit.setOnAction(a -> openPartEditor(component.definitionId()));
                menu.getItems().add(edit);
            }
        } else if (wire != null) {
            setSelection(List.of());
            selectedLabel = null;
            selectedWire = wire.wire().id();
            var start = new MenuItem("Start wire here (tee)");
            start.setOnAction(a -> {
                wireStart = Anchor.of(wire);
                bends.clear();
                redraw();
            });
            var addLabel = new MenuItem("Add net label here...");
            addLabel.setOnAction(a -> labelWire(wire));
            var delete = new MenuItem("Delete wire (Del)");
            delete.setOnAction(a -> deleteSelected());
            var rename = new MenuItem("Rename net " + wire.net().name() + "...");
            rename.setOnAction(a -> renameNet(wire.net()));
            menu.getItems().addAll(start, addLabel, delete, rename);
            showProperties();
            redraw();
        } else if (clip != null) {
            var pasteItem = new MenuItem("Paste here");
            pasteItem.setOnAction(a -> {
                boolean inside = mouseInside;
                mouseInside = true;
                paste();
                mouseInside = inside;
            });
            menu.getItems().add(pasteItem);
        } else {
            return;
        }
        menu.show(canvas, e.getScreenX(), e.getScreenY());
    }

    // ---- Commands ----

    private String nextReference(String kind) {
        String prefix = Symbols.referencePrefix(kind);
        int max = 0;
        for (ComponentView c : project.components()) {
            String reference = c.reference();
            if (reference.startsWith(prefix) && reference.length() > prefix.length()
                    && reference.substring(prefix.length()).chars().allMatch(Character::isDigit)) {
                max = Math.max(max, Integer.parseInt(reference.substring(prefix.length())));
            }
        }
        return prefix + (max + 1);
    }

    /** Rotates one part in place, or a group by 90° clockwise about its centre so the parts keep their layout. */
    private void rotateSelected() {
        List<ComponentView> group = selectedComponents();
        if (group.size() == 1) {
            ComponentView c = group.getFirst();
            workbench.apply(List.of(new MoveOp(c.id().toString(), c.xMm(), c.yMm(), (c.rotation() + 90) % 360)));
        } else if (!group.isEmpty()) {
            double cx = snap(group.stream().mapToDouble(ComponentView::xMm).average().orElse(0));
            double cy = snap(group.stream().mapToDouble(ComponentView::yMm).average().orElse(0));
            var moves = new ArrayList<Operation>();
            for (ComponentView c : group) {
                moves.add(new MoveOp(c.id().toString(), snap(cx - (c.yMm() - cy)), snap(cy + (c.xMm() - cx)),
                        (c.rotation() + 90) % 360));
            }
            workbench.apply(moves);
        }
    }

    private void deleteSelected() {
        if (selectedLabel != null) {
            UUID id = selectedLabel;
            selectedLabel = null;
            workbench.apply(List.of(new DeleteNetLabelOp(id)));
            workbench.status("Deleted net label");
            return;
        }
        if (selectedWire != null && selection.isEmpty()) {
            UUID id = selectedWire;
            selectedWire = null;
            workbench.apply(List.of(new DeleteWireOp(id)));
            workbench.status("Deleted wire (pins stay connected; disconnect pins to change the netlist)");
            return;
        }
        deleteComponents(selectedComponents());
    }

    private void deleteComponents(List<ComponentView> group) {
        if (group.isEmpty()) {
            return;
        }
        setSelection(List.of());
        workbench.apply(group.stream().<Operation>map(c -> new DeleteComponentOp(c.id().toString())).toList());
        workbench.status("Deleted " + String.join(", ", group.stream().map(ComponentView::reference).toList()));
    }

    private void renameNet(NetView net) {
        var dialog = new TextInputDialog(net.name());
        dialog.setHeaderText("Rename net " + net.name());
        dialog.initOwner(canvas.getScene().getWindow());
        dialog.showAndWait().filter(name -> !name.isBlank() && !name.equals(net.name()))
                .ifPresent(name -> workbench.apply(List.of(new RenameNetOp(net.id().toString(), name.strip()))));
    }

    private void updateHint() {
        hint.setText(switch (tool) {
            case SELECT -> "Click or box-select parts \u2022 Shift/Ctrl-click adds \u2022 drag to move \u2022 click pins "
                    + "to wire \u2022 R rotate \u2022 Del delete \u2022 Ctrl+C/V/D copy, paste, duplicate \u2022 middle "
                    + "button or Space+drag pans";
            case PLACE -> "Click to place \u2022 Shift-click places repeatedly \u2022 Esc to stop";
            case WIRE -> "Click a pin or wire \u2022 click empty space to bend \u2022 click a pin or wire to finish "
                    + "\u2022 Backspace undoes a bend \u2022 Esc cancels";
            case LABEL -> "Click a pin or wire and type a net name \u2022 equal labels connect without wires "
                    + "\u2022 Esc to stop";
        });
    }

    // ---- Property sheet ----

    private static Label wrapped(String text) {
        var label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    private void showProperties() {
        properties.getChildren().clear();
        if (project == null) {
            properties.getChildren().add(new Label("No project open"));
            return;
        }
        ComponentView c = selectedComponent();
        if (selectedLabel != null) {
            for (NetView net : project.nets()) {
                for (NetLabelView label : net.labels()) {
                    if (label.id().equals(selectedLabel)) {
                        var title = new Label("Net label " + net.name());
                        title.setFont(Font.font(15));
                        var delete = new Button("Delete label");
                        delete.setOnAction(e -> deleteSelected());
                        var rename = new Button("Rename net...");
                        rename.setOnAction(e -> renameNet(net));
                        properties.getChildren().addAll(title, new Label(label.pinId() != null ? "On a pin"
                                        : "On a wire of the net"), new Label(net.pinIds().size() + " pin(s) and "
                                        + net.labels().size() + " label(s) on this net"), new HBox(6, delete, rename),
                                new Separator(), wrapped("Pins labelled with the same name are one net, with or "
                                        + "without wires between them."));
                        return;
                    }
                }
            }
        }
        if (selection.size() > 1) {
            List<ComponentView> group = selectedComponents();
            var title = new Label(group.size() + " components selected");
            title.setFont(Font.font(15));
            var rotate = new Button("Rotate");
            rotate.setOnAction(e -> rotateSelected());
            var delete = new Button("Delete");
            delete.setOnAction(e -> deleteSelected());
            var copyButton = new Button("Copy");
            copyButton.setOnAction(e -> copy());
            var duplicateButton = new Button("Duplicate");
            duplicateButton.setOnAction(e -> duplicate());
            properties.getChildren().addAll(title, wrapped(String.join(", ",
                            group.stream().map(ComponentView::reference).toList())),
                    new HBox(6, rotate, delete), new HBox(6, copyButton, duplicateButton), new Separator(),
                    wrapped("Drag any selected part to move the group. Shift/Ctrl-click adds or removes parts."));
            return;
        }
        if (c == null && selectedWire != null) {
            for (NetView net : project.nets()) {
                for (WireView wire : net.wires()) {
                    if (wire.id().equals(selectedWire)) {
                        var title = new Label("Wire on " + net.name());
                        title.setFont(Font.font(15));
                        var delete = new Button("Delete wire");
                        delete.setOnAction(e -> deleteSelected());
                        var rename = new Button("Rename net...");
                        rename.setOnAction(e -> renameNet(net));
                        properties.getChildren().addAll(title, new Label(wire.points().size() + " point(s), "
                                        + (wire.fromPinId() == null ? "free" : "pin") + " start, "
                                        + (wire.toPinId() == null ? "free" : "pin") + " end"),
                                new Label(net.pinIds().size() + " pin(s) and " + net.wires().size()
                                        + " wire(s) on this net"), new HBox(6, delete, rename), new Separator(),
                                new Label("Wires are drawing only; pin membership defines connectivity."));
                        return;
                    }
                }
            }
        }
        if (c == null) {
            var title = new Label(project.name());
            title.setFont(Font.font(15));
            var rename = new Button("Rename project...");
            rename.setOnAction(e -> {
                var dialog = new TextInputDialog(project.name());
                dialog.setHeaderText("Project name");
                dialog.initOwner(canvas.getScene().getWindow());
                dialog.showAndWait().filter(n -> !n.isBlank() && !n.equals(project.name()))
                        .ifPresent(n -> workbench.apply(List.of(new RenameProjectOp(n.strip()))));
            });
            properties.getChildren().addAll(title, new Label("Revision " + project.revision()),
                    new Label(project.components().size() + " components, " + project.nets().size() + " nets"),
                    rename, new Separator(), new Label("Select a part to edit its properties."));
            return;
        }
        var title = new Label(c.reference() + " \u2014 " + c.name());
        title.setFont(Font.font(15));
        title.setWrapText(true);
        var grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        var reference = new TextField(c.reference());
        grid.addRow(0, new Label("Reference"), reference);
        var editors = new LinkedHashMap<String, Control>();
        int row = 1;
        for (ParameterView parameter : c.parameters()) {
            String value = c.properties().getOrDefault(parameter.name(), parameter.defaultValue());
            Control editor;
            if (!parameter.allowedValues().isEmpty()) {
                var choice = new ComboBox<String>();
                choice.getItems().setAll(parameter.allowedValues());
                choice.setValue(value);
                editor = choice;
            } else {
                var field = new TextField(value);
                field.setPromptText(parameter.defaultValue());
                editor = field;
            }
            editor.setTooltip(new Tooltip(parameter.name() + (parameter.unit().isEmpty() ? "" : " [" + parameter.unit()
                    + "]") + "; SI prefixes such as p n u m k M are accepted"));
            editors.put(parameter.name(), editor);
            grid.addRow(row++, new Label(parameter.name() + (parameter.unit().isEmpty() ? "" : " (" + parameter.unit()
                    + ")")), editor);
        }
        var apply = new Button("Apply");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            var operations = new ArrayList<Operation>();
            var changed = new LinkedHashMap<String, String>();
            editors.forEach((name, editor) -> {
                String value = editor instanceof ComboBox<?> box ? String.valueOf(box.getValue())
                        : ((TextField) editor).getText().strip();
                if (!value.equals(c.properties().get(name))) {
                    changed.put(name, value);
                }
            });
            if (!changed.isEmpty()) {
                operations.add(new SetPropertiesOp(c.id().toString(), changed));
            }
            if (!reference.getText().strip().equals(c.reference())) {
                operations.add(new SetReferenceOp(c.id().toString(), reference.getText().strip()));
            }
            if (!operations.isEmpty()) {
                workbench.apply(operations);
            }
        });
        var rotate = new Button("Rotate");
        rotate.setOnAction(e -> rotateSelected());
        var delete = new Button("Delete");
        delete.setOnAction(e -> deleteSelected());
        properties.getChildren().addAll(title, new Label(c.definitionId() + " v" + c.definitionVersion()
                        + (c.physical() ? "" : " \u2022 virtual (no footprint)")), grid,
                new HBox(6, apply, rotate, delete), new Separator(), new Label("Pins"));
        if (c.definitionId().startsWith("project.")) {
            var editPart = new Button("Edit part " + c.definitionId() + "...");
            editPart.setOnAction(e -> openPartEditor(c.definitionId()));
            properties.getChildren().add(3, editPart);
        }
        for (PinView pin : c.pins()) {
            NetView net = netByPin.get(pin.id());
            var label = new Label(pin.number() + " (" + pin.name() + ") \u2192 " + (net == null ? "unconnected" : net.name()));
            label.setTextFill(net == null && pin.required() ? Color.FIREBRICK : Color.BLACK);
            var line = new HBox(6, label);
            if (net != null) {
                var disconnect = new Button("\u2715");
                disconnect.setTooltip(new Tooltip("Disconnect this pin"));
                disconnect.setOnAction(e -> workbench.apply(List.of(new DisconnectOp(pin.id().toString()))));
                var rename = new Button("Rename net");
                rename.setOnAction(e -> renameNet(net));
                line.getChildren().addAll(disconnect, rename);
            }
            properties.getChildren().add(line);
        }
    }

    // ---- Rendering ----

    void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        g.setFill(Color.web("#fbfbf8"));
        g.fillRect(0, 0, width, height);
        if (project == null) {
            g.setFill(Color.GRAY);
            g.fillText("Create or open a project (File menu) to start drawing a schematic.", 20, 30);
            return;
        }
        double step = Symbols.GRID * scale;
        if (step >= 8) {
            g.setFill(Color.web("#d5d8dc"));
            double startX = offsetX % step;
            double startY = offsetY % step;
            for (double x = startX; x < width; x += step) {
                for (double y = startY; y < height; y += step) {
                    g.fillRect(x - 0.5, y - 0.5, 1.2, 1.2);
                }
            }
        }
        var positions = new HashMap<UUID, double[]>();
        var components = new ArrayList<ComponentView>();
        var moving = new HashSet<UUID>();
        double ddx = 0;
        double ddy = 0;
        if (dragging != null && dragMoved) {
            movingComponents().forEach(c -> moving.add(c.id()));
            ddx = snap(mmX(mouseX) - dragDx) - dragging.xMm();
            ddy = snap(mmY(mouseY) - dragDy) - dragging.yMm();
        }
        for (ComponentView c : project.components()) {
            ComponentView shown = c;
            if (moving.contains(c.id())) {
                shown = withPosition(c, c.xMm() + ddx, c.yMm() + ddy);
            }
            components.add(shown);
            for (PinView p : shown.pins()) {
                positions.put(p.id(), Symbols.pin(shown, p));
            }
        }
        drawNets(g, positions);
        for (ComponentView c : components) {
            boolean isSelected = selection.contains(c.id());
            g.save();
            g.translate(sx(c.xMm()), sy(c.yMm()));
            g.scale(scale, scale);
            g.rotate(c.rotation());
            g.setStroke(isSelected ? SELECTED : INK);
            g.setFill(isSelected ? SELECTED : INK);
            Symbols.drawBody(g, c);
            g.restore();
            g.setFill(isSelected ? SELECTED : INK);
            Symbols.drawLabels(g, c, sx(c.xMm()), sy(c.yMm()), scale);
            for (PinView p : c.pins()) {
                if (Symbols.hidden(c.kind(), p.number()) && !netByPin.containsKey(p.id())) {
                    continue;
                }
                double[] at = positions.get(p.id());
                boolean connected = netByPin.containsKey(p.id());
                double r = Math.max(2, scale * 0.35);
                if (connected) {
                    g.setFill(WIRE);
                    g.fillOval(sx(at[0]) - r, sy(at[1]) - r, 2 * r, 2 * r);
                } else {
                    g.setStroke(p.required() ? Color.FIREBRICK : Color.GRAY);
                    g.setLineWidth(1.2);
                    g.strokeOval(sx(at[0]) - r, sy(at[1]) - r, 2 * r, 2 * r);
                }
            }
        }
        drawLabels(g, positions, components);
        if (rubberBand && dragMoved) {
            double x = Math.min(pressX, mouseX);
            double y = Math.min(pressY, mouseY);
            g.setFill(Color.web("#1565c0", 0.08));
            g.fillRect(x, y, Math.abs(mouseX - pressX), Math.abs(mouseY - pressY));
            g.setStroke(SELECTED);
            g.setLineWidth(1);
            g.setLineDashes(4, 3);
            g.strokeRect(x, y, Math.abs(mouseX - pressX), Math.abs(mouseY - pressY));
            g.setLineDashes();
        }
        if (wireStart != null) {
            var preview = new ArrayList<double[]>();
            preview.add(new double[] {wireStart.x(), wireStart.y()});
            preview.addAll(bends);
            PinHit target = pinAt(mouseX, mouseY);
            preview.add(target != null ? new double[] {target.x(), target.y()}
                    : new double[] {snap(mmX(mouseX)), snap(mmY(mouseY))});
            var path = WireGeometry.orthogonal(preview);
            g.setStroke(SELECTED);
            g.setLineWidth(1.5);
            g.setLineDashes(6, 4);
            stroke(g, path);
            g.setLineDashes();
        }
        if (tool == Tool.PLACE && palette.getSelectionModel().getSelectedItem() != null) {
            g.setStroke(Color.web("#90a4ae"));
            g.setLineWidth(1);
            double x = sx(snap(mmX(mouseX)));
            double y = sy(snap(mmY(mouseY)));
            g.strokeLine(x - 8, y, x + 8, y);
            g.strokeLine(x, y - 8, x, y + 8);
        }
    }

    private void drawNets(GraphicsContext g, Map<UUID, double[]> positions) {
        double lineWidth = Math.max(1.2, scale * 0.22);
        g.setFont(Font.font(Math.max(9, Math.min(12, scale * 1.2))));
        for (NetView net : project.nets()) {
            var paths = new LinkedHashMap<UUID, List<double[]>>();
            for (WireView wire : net.wires()) {
                var path = WireGeometry.path(wire, positions);
                if (path != null) {
                    paths.put(wire.id(), path);
                }
            }
            var groups = WireGeometry.groups(net, positions, paths);
            var points = new ArrayList<double[]>();
            var groupOf = new ArrayList<Integer>();
            for (int i = 0; i < groups.size(); i++) {
                for (UUID pin : groups.get(i)) {
                    points.add(positions.get(pin));
                    groupOf.add(i);
                }
            }
            g.setLineWidth(lineWidth);
            for (var entry : paths.entrySet()) {
                g.setStroke(entry.getKey().equals(selectedWire) ? SELECTED : WIRE);
                stroke(g, entry.getValue());
            }
            g.setStroke(WIRE);
            if (paths.isEmpty()) {
                drawTree(g, points, groupOf);
            } else {
                // Pins not yet joined by drawn wires are shown as a dashed ratsnest.
                g.setLineWidth(1);
                g.setLineDashes(4, 4);
                drawTree(g, points, groupOf);
                g.setLineDashes();
                g.setFill(WIRE);
                double r = Math.max(2.5, scale * 0.45);
                for (double[] junction : WireGeometry.junctions(List.copyOf(paths.values()))) {
                    g.fillOval(sx(junction[0]) - r, sy(junction[1]) - r, 2 * r, 2 * r);
                }
            }
            double[] first = !points.isEmpty() ? points.getFirst()
                    : paths.isEmpty() ? null : paths.values().iterator().next().getFirst();
            if (first != null && net.labels().isEmpty()) {
                g.setFill(Color.web("#2e7d32"));
                g.fillText(net.name(), sx(first[0]) + 4, sy(first[1]) - 4);
            }
        }
    }

    /**
     * Net labels as flags pointing at their pin or wire point, on the side away from the part body; the drawn boxes are
     * kept for hit testing.
     */
    private void drawLabels(GraphicsContext g, Map<UUID, double[]> positions, List<ComponentView> components) {
        labelBoxes.clear();
        var centreOfPin = new HashMap<UUID, double[]>();
        for (ComponentView c : components) {
            c.pins().forEach(p -> centreOfPin.put(p.id(), new double[] {c.xMm(), c.yMm()}));
        }
        Font font = Font.font("System", javafx.scene.text.FontWeight.BOLD, Math.max(9, Math.min(13, scale * 1.3)));
        g.setFont(font);
        var measure = new Text();
        measure.setFont(font);
        for (NetView net : project.nets()) {
            measure.setText(net.name());
            double textWidth = measure.getLayoutBounds().getWidth();
            double height = measure.getLayoutBounds().getHeight() + 4;
            double width = textWidth + 10 + height / 2;
            for (NetLabelView label : net.labels()) {
                double[] at = label.pinId() != null ? positions.get(label.pinId())
                        : label.point() == null ? null : new double[] {label.point().xMm(), label.point().yMm()};
                if (at == null) {
                    continue;
                }
                double[] centre = label.pinId() == null ? null : centreOfPin.get(label.pinId());
                boolean left = centre != null && at[0] < centre[0] - 1e-6;
                double ax = sx(at[0]);
                double ay = sy(at[1]);
                double gap = 4;
                double x = left ? ax - gap - width : ax + gap;
                double y = ay - height / 2;
                boolean isSelected = label.id().equals(selectedLabel);
                double tip = height / 2;
                double[] xs = left ? new double[] {x, x + width - tip, x + width, x + width - tip, x}
                        : new double[] {x, x + tip, x + width, x + width, x + tip};
                double[] ys = left ? new double[] {y, y, ay, y + height, y + height}
                        : new double[] {ay, y, y, y + height, y + height};
                g.setFill(isSelected ? Color.web("#e3f2fd") : Color.web("#fffde7"));
                g.fillPolygon(xs, ys, 5);
                g.setStroke(isSelected ? SELECTED : Color.web("#2e7d32"));
                g.setLineWidth(isSelected ? 2 : 1.2);
                g.strokePolygon(xs, ys, 5);
                g.setFill(isSelected ? SELECTED : Color.web("#1b5e20"));
                g.fillText(net.name(), left ? x + 4 : x + tip + 2, y + height - 5);
                labelBoxes.add(new LabelBox(label, net, x, y, width, height));
            }
        }
    }

    private void stroke(GraphicsContext g, List<double[]> path) {
        double[] xs = new double[path.size()];
        double[] ys = new double[path.size()];
        for (int i = 0; i < path.size(); i++) {
            xs[i] = sx(path.get(i)[0]);
            ys[i] = sy(path.get(i)[1]);
        }
        g.strokePolyline(xs, ys, path.size());
    }

    /**
     * Prim's minimum spanning tree with Manhattan distance, drawn as L-shaped segments. Points in the same group are
     * already joined, so they connect at zero cost and only edges between groups are drawn.
     */
    private void drawTree(GraphicsContext g, List<double[]> points, List<Integer> groupOf) {
        if (points.isEmpty()) {
            return;
        }
        boolean[] inTree = new boolean[points.size()];
        double[] best = new double[points.size()];
        int[] parent = new int[points.size()];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        best[0] = 0;
        parent[0] = -1;
        for (int round = 0; round < points.size(); round++) {
            int next = -1;
            for (int i = 0; i < points.size(); i++) {
                if (!inTree[i] && (next < 0 || best[i] < best[next])) {
                    next = i;
                }
            }
            inTree[next] = true;
            if (parent[next] >= 0 && !groupOf.get(parent[next]).equals(groupOf.get(next))) {
                double[] a = points.get(parent[next]);
                double[] b = points.get(next);
                g.strokeLine(sx(a[0]), sy(a[1]), sx(b[0]), sy(a[1]));
                g.strokeLine(sx(b[0]), sy(a[1]), sx(b[0]), sy(b[1]));
            }
            for (int i = 0; i < points.size(); i++) {
                double distance = groupOf.get(i).equals(groupOf.get(next)) ? 0
                        : Math.abs(points.get(i)[0] - points.get(next)[0])
                        + Math.abs(points.get(i)[1] - points.get(next)[1]);
                if (!inTree[i] && distance < best[i]) {
                    best[i] = distance;
                    parent[i] = next;
                }
            }
        }
    }

    private static ComponentView withPosition(ComponentView c, double x, double y) {
        return new ComponentView(c.id(), c.sheetId(), c.reference(), c.definitionId(), c.definitionVersion(), c.name(),
                c.kind(), x, y, c.rotation(), c.properties(), c.parameters(), c.physical(), c.pins());
    }
}
