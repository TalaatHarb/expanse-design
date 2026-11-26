package net.talaatharb.expanse.desktop;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Shape3D;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.FileChooser;
import net.talaatharb.expanse.desktop.model.MeshData;
import net.talaatharb.expanse.desktop.model.ModelImporters;
import net.talaatharb.expanse.dto.ApiModels.AssemblyView;
import net.talaatharb.expanse.dto.ApiModels.Diagnostic;
import net.talaatharb.expanse.dto.ApiModels.Snapshot;
import net.talaatharb.expanse.dto.ApiModels.SolidView;

/**
 * 3D assembly viewer using the camera rig of Oracle's JavaFX 3D sample application. Design coordinates (X right,
 * Y away, Z up, millimetres) map to JavaFX as {@code (x, y, z) -> (X, -Z, Y)}, a proper rotation, so a design
 * rotation of θ about Z becomes a JavaFX rotation of -θ about Y.
 */
final class AssemblyPane {
    private static final double CAMERA_DISTANCE = 120;
    private static final double DEFAULT_RX = -55;
    private static final double DEFAULT_RY = 20;
    private final Workbench workbench;
    private final ModelImporters importers = ModelImporters.defaults();
    private final BorderPane root = new BorderPane();
    private final Group sceneRoot = new Group();
    private final Xform world = new Xform();
    private final Xform design = new Xform();
    private final Xform axes = new Xform();
    private final Xform imported = new Xform();
    private final Xform cameraXform = new Xform();
    private final Xform cameraXform2 = new Xform();
    private final Xform cameraXform3 = new Xform();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final SubScene subScene;
    private final CheckBox wireframe = new CheckBox("Wireframe");
    private final CheckBox showAxes = new CheckBox("Axes");
    private final ListView<Diagnostic> findings = new ListView<>();
    private final Label info = new Label("Drag to orbit \u2022 scroll / right-drag to zoom \u2022 middle or Shift-drag to pan "
            + "\u2022 Z reset \u2022 T top \u2022 X axes");
    private Snapshot project;
    private AssemblyView assembly;
    private boolean active;
    private double mouseX;
    private double mouseY;

    AssemblyPane(Workbench workbench) {
        this.workbench = workbench;
        buildCamera();
        buildAxes();
        world.getChildren().addAll(design, imported, axes);
        var ambient = new AmbientLight(Color.rgb(110, 110, 110));
        var key = new PointLight(Color.rgb(220, 220, 220));
        key.setTranslateX(-150);
        key.setTranslateY(-300);
        key.setTranslateZ(-200);
        sceneRoot.getChildren().addAll(world, cameraXform, ambient, key);
        subScene = new SubScene(sceneRoot, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#2b2f33"));
        subScene.setCamera(camera);
        var holder = new Pane(subScene);
        holder.setMinSize(200, 200);
        subScene.widthProperty().bind(holder.widthProperty());
        subScene.heightProperty().bind(holder.heightProperty());
        holder.setFocusTraversable(true);
        holder.setOnMousePressed(e -> {
            holder.requestFocus();
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
        });
        holder.setOnMouseDragged(this::dragged);
        holder.setOnScroll(this::scrolled);
        holder.setOnKeyPressed(this::keyPressed);
        subScene.setOnMouseClicked(this::picked);

        showAxes.setSelected(true);
        showAxes.selectedProperty().addListener((o, old, now) -> axes.setVisible(now));
        wireframe.selectedProperty().addListener(o -> applyDrawMode());
        var reset = new Button("Reset view");
        reset.setOnAction(e -> resetCamera());
        var top = new Button("Top view");
        top.setOnAction(e -> topView());
        var importModel = new Button("Import model...");
        importModel.setTooltip(new Tooltip("Overlay an STL or OBJ model (millimetres, Z up) on the board"));
        importModel.setOnAction(e -> importModel());
        var clearModel = new Button("Clear model");
        clearModel.setOnAction(e -> imported.getChildren().clear());
        var refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        var toolbar = new HBox(6, refresh, reset, top, wireframe, showAxes, new Separator(Orientation.VERTICAL),
                importModel, clearModel);
        toolbar.setPadding(new Insets(4));
        findings.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.severity() + " " + item.code() + "\n" + item.message());
                setWrapText(true);
                setPrefWidth(0);
            }
        });
        findings.setPlaceholder(new Label("No mechanical findings"));
        var right = new VBox(6, new Label("Mechanical checks"), findings);
        right.setPadding(new Insets(6));
        right.setPrefWidth(260);
        VBox.setVgrow(findings, javafx.scene.layout.Priority.ALWAYS);
        info.setPadding(new Insets(4));
        root.setTop(toolbar);
        root.setCenter(holder);
        root.setRight(right);
        root.setBottom(info);
    }

    Node node() {
        return root;
    }

    void setActive(boolean active) {
        this.active = active;
        if (active && project != null && (assembly == null || assembly.revision() != project.revision()
                || !assembly.projectId().equals(project.id()))) {
            refresh();
        }
    }

    void show(Snapshot snapshot) {
        boolean newProject = snapshot == null || project == null || !project.id().equals(snapshot.id());
        project = snapshot;
        if (snapshot == null) {
            assembly = null;
            design.getChildren().clear();
            findings.getItems().clear();
            return;
        }
        if (newProject) {
            imported.getChildren().clear();
            resetCamera();
        }
        if (active) {
            refresh();
        }
    }

    void refresh() {
        if (project == null) {
            return;
        }
        UUID id = project.id();
        workbench.run(() -> workbench.api().assembly(id), result -> {
            if (project != null && project.id().equals(result.projectId())) {
                render(result);
            }
        });
    }

    // ---- Scene construction ----

    private void buildCamera() {
        cameraXform.getChildren().add(cameraXform2);
        cameraXform2.getChildren().add(cameraXform3);
        cameraXform3.getChildren().add(camera);
        camera.setNearClip(0.1);
        camera.setFarClip(10_000);
        camera.setFieldOfView(35);
        resetCamera();
    }

    private void resetCamera() {
        camera.setTranslateZ(-CAMERA_DISTANCE);
        cameraXform.setRotate(DEFAULT_RX, DEFAULT_RY, 0);
        cameraXform2.setTranslate(0, 0, 0);
        cameraXform3.setRotate(0, 0, 0);
        fitDistance();
    }

    private void topView() {
        cameraXform.setRotate(-90, 0, 0);
        cameraXform2.setTranslate(0, 0, 0);
        fitDistance();
    }

    private void fitDistance() {
        if (assembly != null) {
            double size = Math.max(assembly.boardWidthMm(), assembly.boardHeightMm());
            camera.setTranslateZ(-Math.max(40, size * 2.2));
        }
    }

    private void buildAxes() {
        double length = 20;
        axes.getChildren().addAll(
                axis(new Box(length, 0.4, 0.4), Color.RED, length / 2, 0, 0),
                axis(new Box(0.4, 0.4, length), Color.LIMEGREEN, 0, 0, length / 2),
                axis(new Box(0.4, length, 0.4), Color.DODGERBLUE, 0, -length / 2, 0));
    }

    private static Node axis(Box box, Color color, double x, double y, double z) {
        box.setMaterial(new PhongMaterial(color));
        box.setTranslateX(x);
        box.setTranslateY(y);
        box.setTranslateZ(z);
        box.setMouseTransparent(true);
        return box;
    }

    void render(AssemblyView view) {
        boolean firstRender = assembly == null || !assembly.projectId().equals(view.projectId());
        assembly = view;
        design.getChildren().clear();
        for (SolidView solid : view.solids()) {
            design.getChildren().add(solid(solid));
        }
        // centre the board at the origin of the scene
        world.setTranslate(-view.boardWidthMm() / 2, 0, -view.boardHeightMm() / 2);
        axes.setTranslate(0, 0, 0);
        findings.getItems().setAll(view.findings());
        applyDrawMode();
        if (firstRender) {
            fitDistance();
        }
        workbench.status("3D: " + view.solids().size() + " solids, max height "
                + String.format(java.util.Locale.ROOT, "%.1f mm", view.maxHeightMm()));
    }

    static Shape3D solid(SolidView solid) {
        Shape3D shape;
        var transforms = new java.util.ArrayList<javafx.scene.transform.Transform>();
        transforms.add(new Translate(solid.xMm(), -solid.zMm(), solid.yMm()));
        transforms.add(new Rotate(-solid.rotationDeg(), Rotate.Y_AXIS));
        switch (solid.shape()) {
            case "cylinder" -> shape = new Cylinder(solid.sizeXMm() / 2, solid.sizeZMm(), 32);
            case "axial" -> {
                shape = new Cylinder(solid.sizeYMm() / 2, solid.sizeXMm(), 32);
                transforms.add(new Rotate(90, Rotate.Z_AXIS));
            }
            default -> shape = new Box(solid.sizeXMm(), solid.sizeZMm(), solid.sizeYMm());
        }
        shape.getTransforms().setAll(transforms);
        shape.setMaterial(material(solid.color()));
        shape.setUserData(solid);
        return shape;
    }

    private static PhongMaterial material(String color) {
        Color diffuse;
        try {
            diffuse = Color.web(color == null ? "#888888" : color);
        } catch (IllegalArgumentException e) {
            diffuse = Color.GRAY;
        }
        var material = new PhongMaterial(diffuse);
        material.setSpecularColor(diffuse.brighter());
        return material;
    }

    private void applyDrawMode() {
        DrawMode mode = wireframe.isSelected() ? DrawMode.LINE : DrawMode.FILL;
        design.getChildren().forEach(n -> ((Shape3D) n).setDrawMode(mode));
        imported.getChildren().forEach(n -> ((Shape3D) n).setDrawMode(mode));
    }

    // ---- Interaction (after Oracle's sample: orbit, zoom, pan) ----

    private void dragged(MouseEvent e) {
        double dx = e.getSceneX() - mouseX;
        double dy = e.getSceneY() - mouseY;
        mouseX = e.getSceneX();
        mouseY = e.getSceneY();
        double modifier = e.isControlDown() ? 0.1 : 1;
        if (e.getButton() == MouseButton.MIDDLE || e.isShiftDown() && e.isPrimaryButtonDown()) {
            double factor = Math.abs(camera.getTranslateZ()) / 600;
            cameraXform2.t.setX(cameraXform2.t.getX() - dx * factor * modifier);
            cameraXform2.t.setY(cameraXform2.t.getY() - dy * factor * modifier);
        } else if (e.isPrimaryButtonDown()) {
            cameraXform.ry.setAngle(cameraXform.ry.getAngle() + dx * 0.3 * modifier);
            cameraXform.rx.setAngle(Math.max(-90, Math.min(90, cameraXform.rx.getAngle() - dy * 0.3 * modifier)));
        } else if (e.isSecondaryButtonDown()) {
            zoom(dy * 0.5 * modifier);
        }
    }

    private void scrolled(ScrollEvent e) {
        zoom(e.getDeltaY() * 0.25);
    }

    private void zoom(double amount) {
        double distance = camera.getTranslateZ() + amount * Math.abs(camera.getTranslateZ()) / 100;
        camera.setTranslateZ(Math.max(-5_000, Math.min(-5, distance)));
    }

    private void keyPressed(KeyEvent e) {
        switch (e.getCode()) {
            case Z -> resetCamera();
            case T -> topView();
            case X -> showAxes.setSelected(!showAxes.isSelected());
            case W -> wireframe.setSelected(!wireframe.isSelected());
            default -> {
                return;
            }
        }
        e.consume();
    }

    private void picked(MouseEvent e) {
        if (!e.isStillSincePress()) {
            return;
        }
        Node node = e.getPickResult().getIntersectedNode();
        if (node != null && node.getUserData() instanceof SolidView solid) {
            info.setText(solid.label() + " (" + solid.role() + ", " + solid.shape() + ") at "
                    + String.format(java.util.Locale.ROOT, "%.2f, %.2f mm, %.2f \u00d7 %.2f \u00d7 %.2f mm",
                    solid.xMm(), solid.yMm(), solid.sizeXMm(), solid.sizeYMm(), solid.sizeZMm()));
            workbench.select(solid.ownerId());
        } else if (node != null && node.getUserData() instanceof String model) {
            info.setText("Imported model: " + model);
        }
    }

    // ---- Imported models ----

    private void importModel() {
        var chooser = new FileChooser();
        chooser.setTitle("Import 3D model");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("3D models (*.stl, *.obj)", "*.stl", "*.obj"));
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) {
            return;
        }
        workbench.run(() -> {
            try {
                return importers.read(file.toPath());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not import " + file.getName() + ": " + e.getMessage(), e);
            }
        }, mesh -> {
            var view = meshView(mesh);
            view.setUserData(file.getName());
            imported.getChildren().setAll(view);
            applyDrawMode();
            workbench.status("Imported " + file.getName() + ": " + mesh.triangleCount() + " triangles");
        });
    }

    /** Converts a Z-up model to a JavaFX mesh with the same axis mapping as the design solids. */
    static MeshView meshView(MeshData data) {
        float[] source = data.points();
        float[] points = new float[source.length];
        for (int i = 0; i < source.length; i += 3) {
            points[i] = source[i];
            points[i + 1] = -source[i + 2];
            points[i + 2] = source[i + 1];
        }
        int[] triangles = data.triangles();
        int[] faces = new int[triangles.length * 2];
        for (int i = 0; i < triangles.length; i++) {
            faces[2 * i] = triangles[i];
        }
        var mesh = new TriangleMesh();
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(0, 0);
        mesh.getFaces().setAll(faces);
        var view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(material("#b0bec5"));
        return view;
    }
}
