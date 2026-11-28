package net.talaatharb.expanse.desktop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import net.talaatharb.expanse.dto.ApiModels.*;

/**
 * PCB layout: board outline, footprints, pads, traces, vias, copper zones, ratsnest and design-rule findings. Y points
 * up. Tools: route (pad to pad), via (click to drop a via on the chosen net) and zone (click the outline corners,
 * double-click or Enter to finish).
 */
final class PcbPane {
    private static final double GRID = 1.27;
    private final Workbench workbench;
    private final BorderPane root = new BorderPane();
    private final Canvas canvas = new Canvas(800, 600);
    private final ComboBox<String> layer = new ComboBox<>();
    private final TextField width = new TextField("0.4");
    private final ListView<Diagnostic> findings = new ListView<>();
    private final Label summary = new Label();
    private Snapshot project;
    private BoardAnalysis analysis;
    private boolean active;
    private double scale = 6;
    private double offsetX = 60;
    private double offsetY = 500;
    private UUID fittedProject;
    private PadView routeStart;
    private UUID selectedTrace;
    private PlacementView dragging;
    private double dragDx;
    private double dragDy;
    private double pressX;
    private double pressY;
    private double mouseX;
    private double mouseY;
    private boolean moved;
    private boolean panning;
    private Set<UUID> highlighted = Set.of();
    private final ComboBox<String> net = new ComboBox<>();
    private final ToggleGroup tools = new ToggleGroup();
    private final ToggleButton routeTool = new ToggleButton("Route");
    private final ToggleButton viaTool = new ToggleButton("Via");
    private final ToggleButton zoneTool = new ToggleButton("Zone");
    private final List<PointView> zoneDraft = new java.util.ArrayList<>();
    private UUID selectedVia;
    private UUID selectedZone;

    PcbPane(Workbench workbench) {
        this.workbench = workbench;
        layer.getItems().setAll("top", "bottom");
        layer.setValue("top");
        width.setPrefColumnCount(4);
        var sync = new Button("Sync from schematic");
        sync.setTooltip(new Tooltip("Place every physical part that is not on the board yet"));
        sync.setOnAction(e -> workbench.apply(List.of(new SyncBoardOp())));
        var routeAll = new Button("Route all airwires");
        routeAll.setOnAction(e -> routeAll());
        var board = new Button("Board...");
        board.setOnAction(e -> editBoard());
        var deleteTrace = new Button("Delete trace");
        deleteTrace.setOnAction(e -> deleteSelectedTrace());
        var deleteAllTraces = new Button("Delete all traces");
        deleteAllTraces.setId("pcb-delete-all-traces");
        deleteAllTraces.setTooltip(new Tooltip("Remove every routed trace and via in one revision; placements and "
                + "zones are kept"));
        deleteAllTraces.setOnAction(e -> deleteAllTraces());
        var rotate = new Button("Rotate part");
        rotate.setOnAction(e -> rotateSelected());
        var fit = new Button("Fit");
        fit.setOnAction(e -> {
            fit();
            redraw();
        });
        var autoroute = new Button("Autoroute");
        autoroute.setId("pcb-autoroute");
        autoroute.setTooltip(new Tooltip("Route every unrouted connection on both layers with vias, keeping the "
                + "board clearance"));
        autoroute.setOnAction(e -> autoroute());
        var pour = new Button("Pour board");
        pour.setId("pcb-pour");
        pour.setTooltip(new Tooltip("Add a copper zone covering the whole board on the chosen net and layer"));
        pour.setOnAction(e -> pourBoard());
        routeTool.setToggleGroup(tools);
        viaTool.setToggleGroup(tools);
        zoneTool.setToggleGroup(tools);
        routeTool.setSelected(true);
        routeTool.setTooltip(new Tooltip("Click a pad, then another pad on the same net"));
        viaTool.setTooltip(new Tooltip("Click to place a via on the chosen net"));
        zoneTool.setTooltip(new Tooltip("Click the zone corners; double-click or Enter finishes, Esc cancels"));
        tools.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            routeStart = null;
            zoneDraft.clear();
            redraw();
        });
        net.setId("pcb-net");
        net.setPromptText("Net");
        net.setPrefWidth(110);
        net.setTooltip(new Tooltip("Net for new vias and zones"));
        var toolbar = new javafx.scene.layout.FlowPane(6, 4, sync, autoroute, routeAll, board,
                new Separator(Orientation.VERTICAL), routeTool, viaTool, zoneTool, new Label("Net"), net, pour,
                new Separator(Orientation.VERTICAL), new Label("Layer"), layer, new Label("Width (mm)"), width,
                deleteTrace, deleteAllTraces, rotate, fit);
        toolbar.setPadding(new Insets(4));

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
            if (routeStart != null || !zoneDraft.isEmpty()) {
                redraw();
            }
        });
        canvas.setOnScroll(this::zoom);
        canvas.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DELETE, BACK_SPACE -> deleteSelectedTrace();
                case R -> rotateSelected();
                case ENTER -> finishZone();
                case ESCAPE -> {
                    routeStart = null;
                    selectedTrace = null;
                    selectedVia = null;
                    selectedZone = null;
                    zoneDraft.clear();
                    redraw();
                }
                default -> {
                    return;
                }
            }
            e.consume();
        });

        findings.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.severity() + " " + item.code() + "\n" + item.message());
                setWrapText(true);
                setPrefWidth(0);
                setTextFill(item != null && item.severity().equals("ERROR") ? Color.FIREBRICK : Color.BLACK);
            }
        });
        findings.getSelectionModel().selectedItemProperty().addListener((o, old, now) -> {
            highlighted = now == null ? Set.of() : new HashSet<>(now.affectedIds());
            redraw();
        });
        summary.setWrapText(true);
        var right = new VBox(6, new Label("Design rule check"), summary, findings);
        right.setPadding(new Insets(6));
        right.setPrefWidth(280);
        VBox.setVgrow(findings, javafx.scene.layout.Priority.ALWAYS);
        root.setTop(toolbar);
        root.setCenter(holder);
        root.setRight(right);
    }

    Node node() {
        return root;
    }

    void setActive(boolean active) {
        this.active = active;
        if (active) {
            refreshAnalysis();
        }
    }

    void show(Snapshot snapshot) {
        project = snapshot;
        dragging = null;
        if (snapshot == null) {
            analysis = null;
            findings.getItems().clear();
        } else {
            if (!snapshot.id().equals(fittedProject)) {
                fittedProject = snapshot.id();
                fit();
            }
            String chosen = net.getValue();
            var names = snapshot.nets().stream().map(NetView::name).sorted().toList();
            if (!names.equals(net.getItems())) {
                net.getItems().setAll(names);
            }
            net.setValue(names.contains(chosen) ? chosen
                    : names.contains("GND") ? "GND" : names.isEmpty() ? null : names.getFirst());
            if (routeStart != null && snapshot.board().pads().stream()
                    .noneMatch(p -> p.pinId().equals(routeStart.pinId()))) {
                routeStart = null;
            }
            if (active) {
                refreshAnalysis();
            }
        }
        redraw();
    }

    void selectionChanged() {
        redraw();
    }

    private void refreshAnalysis() {
        if (project == null) {
            return;
        }
        UUID id = project.id();
        long revision = project.revision();
        workbench.run(() -> workbench.api().analyzeBoard(id), result -> {
            if (project != null && project.id().equals(id) && revision == project.revision()) {
                analysis = result;
                findings.getItems().setAll(result.findings());
                long errors = result.findings().stream().filter(f -> f.severity().equals("ERROR")).count();
                summary.setText(project.board().placements().size() + " footprints, " + project.board().traces().size()
                        + " traces, " + project.board().vias().size() + " vias, " + project.board().zones().size()
                        + " zones, " + result.airwires().size() + " unrouted connection(s), " + errors + " error(s)");
                redraw();
            }
        });
    }

    // ---- Coordinates (board millimetres, Y up) ----

    private double sx(double x) {
        return offsetX + x * scale;
    }

    private double sy(double y) {
        return offsetY - y * scale;
    }

    private double mmX(double sx) {
        return (sx - offsetX) / scale;
    }

    private double mmY(double sy) {
        return (offsetY - sy) / scale;
    }

    private static double snap(double mm) {
        return Math.round(mm / GRID) * GRID;
    }

    private void fit() {
        if (project == null) {
            return;
        }
        double w = Math.max(canvas.getWidth(), 300);
        double h = Math.max(canvas.getHeight(), 300);
        double boardW = project.board().widthMm();
        double boardH = project.board().heightMm();
        scale = Math.max(1, Math.min(40, Math.min((w - 80) / boardW, (h - 80) / boardH)));
        offsetX = (w - boardW * scale) / 2;
        offsetY = (h + boardH * scale) / 2;
    }

    // ---- Interaction ----

    private PadView padAt(double x, double y) {
        if (project == null) {
            return null;
        }
        for (PadView pad : project.board().pads()) {
            if (Math.abs(mmX(x) - pad.xMm()) <= pad.widthMm() / 2 + 0.2
                    && Math.abs(mmY(y) - pad.yMm()) <= pad.heightMm() / 2 + 0.2) {
                return pad;
            }
        }
        return null;
    }

    private PlacementView placementAt(double x, double y) {
        if (project == null) {
            return null;
        }
        for (PlacementView p : project.board().placements()) {
            double[] half = half(p);
            if (Math.abs(mmX(x) - p.xMm()) <= half[0] && Math.abs(mmY(y) - p.yMm()) <= half[1]) {
                return p;
            }
        }
        return null;
    }

    private TraceView traceAt(double x, double y) {
        if (project == null) {
            return null;
        }
        double px = mmX(x);
        double py = mmY(y);
        for (TraceView trace : project.board().traces()) {
            for (int i = 0; i + 1 < trace.points().size(); i++) {
                var a = trace.points().get(i);
                var b = trace.points().get(i + 1);
                if (distanceToSegment(px, py, a, b) <= trace.widthMm() / 2 + 3 / scale) {
                    return trace;
                }
            }
        }
        return null;
    }

    private static double distanceToSegment(double px, double py, PointView a, PointView b) {
        double dx = b.xMm() - a.xMm();
        double dy = b.yMm() - a.yMm();
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - a.xMm()) * dx + (py - a.yMm()) * dy) / length));
        return Math.hypot(px - (a.xMm() + t * dx), py - (a.yMm() + t * dy));
    }

    private static double[] half(PlacementView p) {
        boolean quarter = p.rotation() % 180 != 0;
        return new double[]{(quarter ? p.courtyardHeightMm() : p.courtyardWidthMm()) / 2,
                (quarter ? p.courtyardWidthMm() : p.courtyardHeightMm()) / 2};
    }

    private void pressed(MouseEvent e) {
        canvas.requestFocus();
        pressX = e.getX();
        pressY = e.getY();
        mouseX = e.getX();
        mouseY = e.getY();
        moved = false;
        if (project == null) {
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) {
            panning = true;
            return;
        }
        if (viaTool.isSelected()) {
            if (requireNet()) {
                workbench.apply(List.of(new AddViaOp(net.getValue(), snap(mmX(e.getX())), snap(mmY(e.getY())), null,
                        null)));
            }
            return;
        }
        if (zoneTool.isSelected()) {
            if (e.getClickCount() >= 2) {
                finishZone();
            } else {
                zoneDraft.add(new PointView(snap(mmX(e.getX())), snap(mmY(e.getY()))));
                workbench.status("Zone: " + zoneDraft.size() + " corner(s); double-click or Enter to finish");
                redraw();
            }
            return;
        }
        PadView pad = padAt(e.getX(), e.getY());
        if (pad != null) {
            if (routeStart == null) {
                routeStart = pad;
                workbench.select(pad.componentId());
                workbench.status("Routing from " + pad.reference() + "." + pad.pinNumber()
                        + "; click a pad on the same net, Esc to cancel");
            } else if (!routeStart.pinId().equals(pad.pinId())) {
                var from = routeStart;
                routeStart = null;
                Double traceWidth = traceWidth();
                if (traceWidth != null) {
                    workbench.apply(List.of(new RouteOp(from.pinId().toString(), pad.pinId().toString(),
                            layer.getValue(), traceWidth)));
                }
            }
            redraw();
            return;
        }
        routeStart = null;
        ViaView via = viaAt(e.getX(), e.getY());
        TraceView trace = via == null ? traceAt(e.getX(), e.getY()) : null;
        PlacementView placement = placementAt(e.getX(), e.getY());
        selectedVia = placement == null ? via == null ? null : via.id() : null;
        selectedTrace = placement == null && trace != null ? trace.id() : null;
        ZoneView zone = placement == null && via == null && trace == null ? zoneAt(e.getX(), e.getY()) : null;
        selectedZone = zone == null ? null : zone.id();
        if (placement != null) {
            workbench.select(placement.componentId());
            dragging = placement;
            dragDx = mmX(e.getX()) - placement.xMm();
            dragDy = mmY(e.getY()) - placement.yMm();
        } else {
            if (trace == null && via == null) {
                workbench.select(null);
            }
            panning = true;
        }
        redraw();
    }

    private void dragged(MouseEvent e) {
        if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
            moved = true;
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
        if (dragging != null && moved) {
            double x = snap(mmX(e.getX()) - dragDx);
            double y = snap(mmY(e.getY()) - dragDy);
            if (x != dragging.xMm() || y != dragging.yMm()) {
                workbench.apply(List.of(new PlaceFootprintOp(dragging.componentId().toString(), x, y, null)));
            }
        }
        dragging = null;
        panning = false;
        redraw();
    }

    private void zoom(ScrollEvent e) {
        double factor = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
        double x = mmX(e.getX());
        double y = mmY(e.getY());
        scale = Math.max(1, Math.min(80, scale * factor));
        offsetX = e.getX() - x * scale;
        offsetY = e.getY() + y * scale;
        redraw();
    }

    private Double traceWidth() {
        try {
            double value = Double.parseDouble(width.getText().strip());
            if (value <= 0 || value > 10) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            workbench.error("Trace width must be a number of millimetres between 0 and 10.");
            return null;
        }
    }

    private void routeAll() {
        if (analysis == null || analysis.airwires().isEmpty()) {
            workbench.status("Nothing to route");
            return;
        }
        Double traceWidth = traceWidth();
        if (traceWidth == null) {
            return;
        }
        List<Operation> operations = analysis.airwires().stream().map(a -> (Operation) new RouteOp(
                a.fromPinId().toString(), a.toPinId().toString(), layer.getValue(), traceWidth)).toList();
        workbench.apply(operations);
        workbench.status("Routed " + operations.size() + " connection(s); check the DRC list for crossings");
    }

    private void deleteSelectedTrace() {
        if (selectedTrace != null) {
            UUID trace = selectedTrace;
            selectedTrace = null;
            workbench.apply(List.of(new DeleteTraceOp(trace)));
        } else if (selectedVia != null) {
            UUID via = selectedVia;
            selectedVia = null;
            workbench.apply(List.of(new DeleteViaOp(via)));
        } else if (selectedZone != null) {
            UUID zone = selectedZone;
            selectedZone = null;
            workbench.apply(List.of(new DeleteZoneOp(zone)));
        }
    }

    private boolean requireNet() {
        if (net.getValue() == null) {
            workbench.error("Choose a net for the via or zone first (connect some pins in the schematic).");
            return false;
        }
        return true;
    }

    private void finishZone() {
        if (zoneDraft.size() < 3) {
            if (zoneTool.isSelected()) {
                workbench.status("A zone needs at least three corners");
            }
            return;
        }
        var outline = List.copyOf(zoneDraft);
        zoneDraft.clear();
        if (requireNet()) {
            workbench.apply(List.of(new AddZoneOp(net.getValue(), layer.getValue(), outline, null)));
        }
        redraw();
    }

    private void pourBoard() {
        if (project == null || !requireNet()) {
            return;
        }
        double inset = Math.max(0.5, project.board().clearanceMm());
        double w = project.board().widthMm() - inset;
        double h = project.board().heightMm() - inset;
        workbench.apply(List.of(new AddZoneOp(net.getValue(), layer.getValue(), List.of(new PointView(inset, inset),
                new PointView(w, inset), new PointView(w, h), new PointView(inset, h)), null)));
    }

    private void autoroute() {
        Double traceWidth = traceWidth();
        if (traceWidth != null && project != null) {
            workbench.apply(List.of(new AutorouteOp(null, traceWidth, null, null)));
        }
    }

    private ViaView viaAt(double x, double y) {
        if (project == null) {
            return null;
        }
        return project.board().vias().stream().filter(v -> Math.hypot(mmX(x) - v.xMm(), mmY(y) - v.yMm())
                <= v.diameterMm() / 2 + 2 / scale).findFirst().orElse(null);
    }

    private ZoneView zoneAt(double x, double y) {
        if (project == null) {
            return null;
        }
        double px = mmX(x);
        double py = mmY(y);
        var zones = project.board().zones();
        for (int z = zones.size() - 1; z >= 0; z--) {
            var outline = zones.get(z).outline();
            boolean inside = false;
            for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
                var a = outline.get(i);
                var b = outline.get(j);
                if ((a.yMm() > py) != (b.yMm() > py)
                        && px < (b.xMm() - a.xMm()) * (py - a.yMm()) / (b.yMm() - a.yMm()) + a.xMm()) {
                    inside = !inside;
                }
            }
            if (inside) {
                return zones.get(z);
            }
        }
        return null;
    }

    private void deleteAllTraces() {
        if (project == null || project.board().traces().isEmpty() && project.board().vias().isEmpty()) {
            workbench.status("No traces to delete");
            return;
        }
        int count = project.board().traces().size();
        int vias = project.board().vias().size();
        var confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete all " + count + " trace(s)"
                + (vias == 0 ? "" : " and " + vias + " via(s)") + "? Footprint placements and zones are kept.",
                ButtonType.YES, ButtonType.NO);
        confirm.initOwner(canvas.getScene().getWindow());
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }
        selectedTrace = null;
        selectedVia = null;
        workbench.apply(deleteAllTracesOperations(project.board()));
        workbench.status("Deleted " + count + " trace(s)" + (vias == 0 ? "" : " and " + vias + " via(s)"));
    }

    static List<Operation> deleteAllTracesOperations(BoardView board) {
        return java.util.stream.Stream.concat(board.traces().stream().map(t -> (Operation) new DeleteTraceOp(t.id())),
                board.vias().stream().map(v -> (Operation) new DeleteViaOp(v.id()))).toList();
    }

    private void rotateSelected() {
        UUID id = workbench.selected();
        if (project == null || id == null) {
            return;
        }
        project.board().placements().stream().filter(p -> p.componentId().equals(id)).findFirst().ifPresent(p ->
                workbench.apply(List.of(new PlaceFootprintOp(id.toString(), p.xMm(), p.yMm(),
                        (p.rotation() + 90) % 360))));
    }

    private void editBoard() {
        if (project == null) {
            return;
        }
        var board = project.board();
        var w = new TextField(String.valueOf(board.widthMm()));
        var h = new TextField(String.valueOf(board.heightMm()));
        var t = new TextField(String.valueOf(board.thicknessMm()));
        var c = new TextField(String.valueOf(board.clearanceMm()));
        var grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.addRow(0, new Label("Width (mm)"), w);
        grid.addRow(1, new Label("Height (mm)"), h);
        grid.addRow(2, new Label("Thickness (mm)"), t);
        grid.addRow(3, new Label("Clearance (mm)"), c);
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Board settings");
        dialog.initOwner(canvas.getScene().getWindow());
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            try {
                workbench.apply(List.of(new SetBoardOp(Double.parseDouble(w.getText()), Double.parseDouble(h.getText()),
                        Double.parseDouble(t.getText()), Double.parseDouble(c.getText()))));
            } catch (NumberFormatException ex) {
                workbench.error("Board settings must be numbers in millimetres.");
            }
        });
    }

    // ---- Rendering ----

    private void polygon(GraphicsContext g, List<PointView> points, boolean closed) {
        int n = points.size();
        for (int i = 0; i + 1 < n || closed && i < n && n > 2; i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % n);
            g.strokeLine(sx(a.xMm()), sy(a.yMm()), sx(b.xMm()), sy(b.yMm()));
            if (i + 1 == n) {
                break;
            }
        }
    }

    void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#1e2124"));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (project == null) {
            g.setFill(Color.LIGHTGRAY);
            g.fillText("Open a project to lay out its board.", 20, 30);
            return;
        }
        var board = project.board();
        g.setFill(Color.web("#204a2a"));
        g.fillRect(sx(0), sy(board.heightMm()), board.widthMm() * scale, board.heightMm() * scale);
        g.setStroke(Color.web("#e0c341"));
        g.setLineWidth(1.5);
        g.strokeRect(sx(0), sy(board.heightMm()), board.widthMm() * scale, board.heightMm() * scale);
        for (String side : List.of("bottom", "top")) {
            Color copper = side.equals("top") ? Color.web("#d64541") : Color.web("#3b78e7");
            for (ZoneView zone : board.zones()) {
                if (!zone.layer().equals(side)) {
                    continue;
                }
                boolean selectedZoneNow = zone.id().equals(selectedZone) || highlighted.contains(zone.id());
                g.setFill(copper.deriveColor(0, 1, 1, selectedZoneNow ? 0.55 : 0.35));
                for (RectView r : zone.fill()) {
                    g.fillRect(sx(r.xMm()), sy(r.yMm() + r.heightMm()), r.widthMm() * scale, r.heightMm() * scale);
                }
                g.setStroke(selectedZoneNow ? Color.WHITE : copper);
                g.setLineWidth(1);
                g.setLineDashes(6, 3);
                polygon(g, zone.outline(), true);
                g.setLineDashes();
            }
        }
        g.setLineCap(StrokeLineCap.ROUND);
        for (String side : List.of("bottom", "top")) {
            for (TraceView trace : board.traces()) {
                if (!trace.layer().equals(side)) {
                    continue;
                }
                boolean selected = trace.id().equals(selectedTrace) || highlighted.contains(trace.id());
                g.setStroke(selected ? Color.WHITE : side.equals("top") ? Color.web("#d64541cc") : Color.web("#3b78e7cc"));
                g.setLineWidth(Math.max(1, trace.widthMm() * scale));
                for (int i = 0; i + 1 < trace.points().size(); i++) {
                    var a = trace.points().get(i);
                    var b = trace.points().get(i + 1);
                    g.strokeLine(sx(a.xMm()), sy(a.yMm()), sx(b.xMm()), sy(b.yMm()));
                }
            }
        }
        g.setLineCap(StrokeLineCap.SQUARE);
        UUID selected = workbench.selected();
        g.setFont(Font.font(Math.max(9, Math.min(13, scale * 1.4))));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        for (PlacementView p : board.placements()) {
            double x = p.xMm();
            double y = p.yMm();
            if (dragging != null && moved && p.componentId().equals(dragging.componentId())) {
                x = snap(mmX(mouseX) - dragDx);
                y = snap(mmY(mouseY) - dragDy);
            }
            double[] half = half(p);
            boolean isSelected = p.componentId().equals(selected) || highlighted.contains(p.componentId());
            g.setStroke(isSelected ? Color.web("#4fc3f7") : Color.web("#c0c0c0"));
            g.setLineWidth(isSelected ? 2 : 1);
            g.strokeRect(sx(x - half[0]), sy(y + half[1]), half[0] * 2 * scale, half[1] * 2 * scale);
            g.setFill(Color.web("#eeeeee"));
            g.fillText(p.reference(), sx(x), sy(y + half[1]) - 2);
        }
        for (PadView pad : board.pads()) {
            double x = pad.xMm();
            double y = pad.yMm();
            if (dragging != null && moved && pad.componentId().equals(dragging.componentId())) {
                x += snap(mmX(mouseX) - dragDx) - dragging.xMm();
                y += snap(mmY(mouseY) - dragDy) - dragging.yMm();
            }
            boolean start = routeStart != null && routeStart.pinId().equals(pad.pinId());
            g.setFill(start ? Color.WHITE : highlighted.contains(pad.pinId()) ? Color.ORANGE : Color.web("#c88a3c"));
            g.fillRect(sx(x - pad.widthMm() / 2), sy(y + pad.heightMm() / 2), pad.widthMm() * scale,
                    pad.heightMm() * scale);
            if (pad.drillMm() > 0) {
                double r = pad.drillMm() / 2 * scale;
                g.setFill(Color.web("#1e2124"));
                g.fillOval(sx(x) - r, sy(y) - r, 2 * r, 2 * r);
            }
        }
        for (ViaView via : board.vias()) {
            double r = via.diameterMm() / 2 * scale;
            double hole = via.drillMm() / 2 * scale;
            boolean isSelected = via.id().equals(selectedVia) || highlighted.contains(via.id());
            g.setFill(isSelected ? Color.WHITE : Color.web("#c8a43c"));
            g.fillOval(sx(via.xMm()) - r, sy(via.yMm()) - r, 2 * r, 2 * r);
            g.setFill(Color.web("#1e2124"));
            g.fillOval(sx(via.xMm()) - hole, sy(via.yMm()) - hole, 2 * hole, 2 * hole);
        }
        if (!zoneDraft.isEmpty()) {
            var draft = new java.util.ArrayList<>(zoneDraft);
            draft.add(new PointView(snap(mmX(mouseX)), snap(mmY(mouseY))));
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.5);
            g.setLineDashes(6, 4);
            polygon(g, draft, true);
            g.setLineDashes();
        }
        if (analysis != null && analysis.revision() == project.revision()) {
            g.setStroke(Color.web("#fff59d"));
            g.setLineWidth(1);
            g.setLineDashes(4, 4);
            for (AirwireView a : analysis.airwires()) {
                g.strokeLine(sx(a.fromXMm()), sy(a.fromYMm()), sx(a.toXMm()), sy(a.toYMm()));
            }
            g.setLineDashes();
        }
        if (routeStart != null) {
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.5);
            g.setLineDashes(6, 4);
            g.strokeLine(sx(routeStart.xMm()), sy(routeStart.yMm()), mouseX, mouseY);
            g.setLineDashes();
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        g.setFill(Color.LIGHTGRAY);
        g.fillText(String.format(java.util.Locale.ROOT, "Board %.1f \u00d7 %.1f mm \u2022 click pad\u2192pad to route "
                        + "\u2022 drag parts \u2022 R rotate \u2022 Del deletes the selected trace, via or zone",
                board.widthMm(),
                board.heightMm()), 10, canvas.getHeight() - 10);
    }
}
