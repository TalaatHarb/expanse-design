package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.talaatharb.expanse.dto.ApiModels.*;

/** Schematic editor: palette, canvas with place/move/rotate/wire tools, property sheet and diagnostics. */
final class SchematicPane {
    private enum Tool { SELECT, PLACE, WIRE }

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
    private final Label hint = new Label();
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
        selectTool.setSelected(true);
        selectTool.setTooltip(new Tooltip("Select, drag to move, drag empty space to pan (S)"));
        placeTool.setTooltip(new Tooltip("Click to place the part selected in the palette (P)"));
        wireTool.setTooltip(new Tooltip("Draw wires (W): click a pin or wire, click empty space to add bends, click a "
                + "pin or wire to finish. Pins can also be wired in Select mode."));
        toolGroup.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            tool = now == placeTool ? Tool.PLACE : now == wireTool ? Tool.WIRE : Tool.SELECT;
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
        var toolbar = new HBox(6, selectTool, placeTool, wireTool, new Separator(Orientation.VERTICAL), rotate, delete,
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
        canvas.setOnScroll(this::zoom);
        canvas.setOnKeyPressed(e -> {
            switch (e.getCode()) {
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
                    } else {
                        selectTool.setSelected(true);
                    }
                    redraw();
                }
                case S -> selectTool.setSelected(true);
                case P -> placeTool.setSelected(true);
                case W -> wireTool.setSelected(true);
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
        if (snapshot != null && !snapshot.id().equals(fittedProject)) {
            fittedProject = snapshot.id();
            fit();
        }
        if (latestDiagnostics != null) {
            diagnostics.getItems().setAll(latestDiagnostics);
        } else if (snapshot == null) {
            diagnostics.getItems().clear();
        }
        showProperties();
        redraw();
    }

    void selectionChanged() {
        if (workbench.selected() != null) {
            selectedWire = null;
        }
        showProperties();
        redraw();
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
        ComponentView component = componentAt(e.getX(), e.getY());
        WireHit wire = pin == null ? wireAt(e.getX(), e.getY()) : null;
        if (e.getButton() == MouseButton.SECONDARY) {
            contextMenu(e, pin, component, wire);
            return;
        }
        if (e.getButton() == MouseButton.MIDDLE) {
            panning = true;
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
        if (component != null) {
            selectedWire = null;
            workbench.select(component.id());
            dragging = component;
            dragDx = mmX(e.getX()) - component.xMm();
            dragDy = mmY(e.getY()) - component.yMm();
        } else if (wire != null) {
            workbench.select(null);
            selectedWire = wire.wire().id();
            showProperties();
            redraw();
        } else {
            selectedWire = null;
            workbench.select(null);
            panning = true;
        }
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
            double x = snap(mmX(e.getX()) - dragDx);
            double y = snap(mmY(e.getY()) - dragDy);
            ComponentView moved = dragging;
            if (x != moved.xMm() || y != moved.yMm()) {
                workbench.apply(List.of(new MoveOp(moved.id().toString(), x, y, null)));
            }
        }
        dragging = null;
        panning = false;
        redraw();
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

    private void contextMenu(MouseEvent e, PinHit pin, ComponentView component, WireHit wire) {
        menu = new ContextMenu();
        if (pin != null) {
            NetView net = netByPin.get(pin.pin().id());
            var start = new MenuItem("Start wire at " + pin.label());
            start.setOnAction(a -> {
                wireStart = Anchor.of(pin);
                bends.clear();
                redraw();
            });
            menu.getItems().add(start);
            if (net != null) {
                var disconnect = new MenuItem("Disconnect " + pin.label() + " from " + net.name());
                disconnect.setOnAction(a -> workbench.apply(List.of(new DisconnectOp(pin.pin().id().toString()))));
                var rename = new MenuItem("Rename net " + net.name() + "...");
                rename.setOnAction(a -> renameNet(net));
                menu.getItems().addAll(disconnect, rename);
            }
        } else if (component != null) {
            workbench.select(component.id());
            var rotate = new MenuItem("Rotate (R)");
            rotate.setOnAction(a -> rotateSelected());
            var delete = new MenuItem("Delete (Del)");
            delete.setOnAction(a -> deleteSelected());
            menu.getItems().addAll(rotate, delete);
            if (component.definitionId().startsWith("project.")) {
                var edit = new MenuItem("Edit part " + component.definitionId() + "...");
                edit.setOnAction(a -> openPartEditor(component.definitionId()));
                menu.getItems().add(edit);
            }
        } else if (wire != null) {
            selectedWire = wire.wire().id();
            workbench.select(null);
            var start = new MenuItem("Start wire here (tee)");
            start.setOnAction(a -> {
                wireStart = Anchor.of(wire);
                bends.clear();
                redraw();
            });
            var delete = new MenuItem("Delete wire (Del)");
            delete.setOnAction(a -> deleteSelected());
            var rename = new MenuItem("Rename net " + wire.net().name() + "...");
            rename.setOnAction(a -> renameNet(wire.net()));
            menu.getItems().addAll(start, delete, rename);
            showProperties();
            redraw();
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

    private void rotateSelected() {
        ComponentView c = selectedComponent();
        if (c != null) {
            workbench.apply(List.of(new MoveOp(c.id().toString(), c.xMm(), c.yMm(), (c.rotation() + 90) % 360)));
        }
    }

    private void deleteSelected() {
        if (selectedWire != null && workbench.selected() == null) {
            UUID id = selectedWire;
            selectedWire = null;
            workbench.apply(List.of(new DeleteWireOp(id)));
            workbench.status("Deleted wire (pins stay connected; disconnect pins to change the netlist)");
            return;
        }
        ComponentView c = selectedComponent();
        if (c != null) {
            workbench.select(null);
            workbench.apply(List.of(new DeleteComponentOp(c.id().toString())));
            workbench.status("Deleted " + c.reference());
        }
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
            case SELECT -> "Drag parts to move \u2022 click pins to wire \u2022 click a wire to select \u2022 R rotate "
                    + "\u2022 Del delete \u2022 wheel zoom";
            case PLACE -> "Click to place \u2022 Shift-click places repeatedly \u2022 Esc to stop";
            case WIRE -> "Click a pin or wire \u2022 click empty space to bend \u2022 click a pin or wire to finish "
                    + "\u2022 Backspace undoes a bend \u2022 Esc cancels";
        });
    }

    // ---- Property sheet ----

    private void showProperties() {
        properties.getChildren().clear();
        if (project == null) {
            properties.getChildren().add(new Label("No project open"));
            return;
        }
        ComponentView c = selectedComponent();
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
        for (ComponentView c : project.components()) {
            ComponentView shown = c;
            if (dragging != null && dragMoved && c.id().equals(dragging.id())) {
                shown = withPosition(c, snap(mmX(mouseX) - dragDx), snap(mmY(mouseY) - dragDy));
            }
            components.add(shown);
            for (PinView p : shown.pins()) {
                positions.put(p.id(), Symbols.pin(shown, p));
            }
        }
        drawNets(g, positions);
        UUID selected = workbench.selected();
        for (ComponentView c : components) {
            boolean isSelected = c.id().equals(selected);
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
            if (first != null) {
                g.setFill(Color.web("#2e7d32"));
                g.fillText(net.name(), sx(first[0]) + 4, sy(first[1]) - 4);
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
