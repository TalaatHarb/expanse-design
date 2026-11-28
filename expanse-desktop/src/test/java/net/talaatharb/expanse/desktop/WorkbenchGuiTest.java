package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import net.talaatharb.expanse.dto.ApiModels.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/**
 * End-to-end GUI journeys: the real desktop, driven by a robot (headless Monocle by default), against the real server
 * started as the local-mode child process. Run with {@code -Dexpanse.ui-tests.headless=false} to watch.
 */
@Tag("gui")
class WorkbenchGuiTest extends FxRobot {
    @TempDir
    static Path temp;
    private static WorkbenchApplication app;

    @BeforeAll
    static void launch() throws Exception {
        Path jar = Path.of(System.getProperty("expanse.ui-tests.server-jar",
                "../expanse-server/target/expanse-server-0.1.0-SNAPSHOT.jar")).toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(jar), "Build expanse-server first; missing " + jar);
        if (!"false".equals(System.getProperty("expanse.ui-tests.headless"))) {
            Map.of("testfx.robot", "glass", "testfx.headless", "true", "glass.platform", "Monocle",
                    "monocle.platform", "Headless", "prism.order", "sw", "java.awt.headless", "true",
                    "headless.geometry", "1600x1000-32").forEach(System::setProperty);
        }
        System.setProperty("testfx.setup.timeout", "120000");
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        FxToolkit.registerPrimaryStage();
        app = (WorkbenchApplication) FxToolkit.setupApplication(WorkbenchApplication.class,
                "--expanse.server-url=http://127.0.0.1:" + port, "--expanse.data-dir=" + temp.resolve("data"),
                "--expanse.settings-file=" + temp.resolve("settings.properties"),
                "--expanse.local-server-jar=" + jar);
        settle();
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS, () -> !palette().getItems().isEmpty());
    }

    @AfterAll
    static void close() throws Exception {
        WorkbenchApplication.fileDialog = null;
        if (app != null) {
            FxToolkit.cleanupApplication(app);
            FxToolkit.cleanupStages();
        }
    }

    @Test
    void buildSimulateSaveAndReopenACircuit() throws Exception {
        newProject("Divider");
        placePart("DC voltage source", 10, 20);
        placePart("Resistor", 30, 10);
        placePart("Resistor", 30, 30);
        placePart("Ground", 10, 45);
        assertThat(project().components()).extracting(ComponentView::reference)
                .containsExactly("V1", "R1", "R2", "GND1");

        wire("V1", 0, "R1", 0);
        wire("R1", 1, "R2", 0);
        wire("R2", 1, "GND1", 0);
        wire("V1", 1, "GND1", 0);
        assertThat(project().nets()).hasSize(3);
        assertThat(project().nets()).flatExtracting(NetView::wires).hasSize(4);
        assertThat(netOf("V1", 1).pinIds()).hasSize(3);

        push(KeyCode.F9);
        settle();
        assertThat(status()).startsWith("Simulation finished");
        interact(() -> lookup(".tab-pane").queryAs(TabPane.class).getSelectionModel().select(0));

        Path file = temp.resolve("divider.json");
        WorkbenchApplication.fileDialog = save -> file.toFile();
        push(KeyCode.SHORTCUT, KeyCode.S);
        settle();
        assertThat(Files.readString(file)).contains("\"R2\"", "\"GND1\"");

        placePart("Resistor", 50, 10);
        assertThat(project().components()).hasSize(5);
        push(KeyCode.SHORTCUT, KeyCode.O);
        clickButton("Don't save");
        clickButton("Use the file");
        settle();
        assertThat(project().components()).extracting(ComponentView::reference)
                .containsExactly("V1", "R1", "R2", "GND1");
        assertThat(project().nets()).flatExtracting(NetView::wires).hasSize(4);
    }

    @Test
    void undoAndRedoRestoreTheSchematic() throws Exception {
        newProject("History");
        placePart("Resistor", 20, 20);
        assertThat(project().components()).hasSize(1);
        push(KeyCode.SHORTCUT, KeyCode.Z);
        settle();
        assertThat(project().components()).isEmpty();
        push(KeyCode.SHORTCUT, KeyCode.Y);
        settle();
        assertThat(project().components()).extracting(ComponentView::reference).containsExactly("R1");
    }

    @Test
    void boxSelectMoveCopyPasteAndDeleteAGroup() throws Exception {
        newProject("Clipboard");
        placePart("Resistor", 20, 20);
        placePart("Resistor", 40, 20);
        wire("R1", 1, "R2", 0);

        drag(screen(5, 8), MouseButton.PRIMARY).moveTo(screen(55, 32)).drop();
        settle();
        assertThat(selected()).containsExactly("R1", "R2");

        double r1x = component("R1").xMm();
        double r2x = component("R2").xMm();
        drag(screen(20, 20), MouseButton.PRIMARY).moveTo(screen(30, 30)).drop();
        settle();
        assertThat(component("R1").xMm()).isCloseTo(r1x + 10.16, within(1e-6));
        assertThat(component("R2").xMm()).isCloseTo(r2x + 10.16, within(1e-6));
        assertThat(component("R2").yMm()).isCloseTo(component("R1").yMm(), within(1e-6));

        push(KeyCode.SHORTCUT, KeyCode.C);
        moveTo(screen(30, 60));
        push(KeyCode.SHORTCUT, KeyCode.V);
        settle();
        assertThat(project().components()).extracting(ComponentView::reference)
                .containsExactly("R1", "R2", "R3", "R4");
        assertThat(selected()).containsExactly("R3", "R4");
        assertThat(netOf("R3", 1).pinIds()).containsExactlyInAnyOrder(pinId("R3", 1), pinId("R4", 0));
        assertThat(netOf("R3", 1).wires()).hasSize(1);

        push(KeyCode.SHORTCUT, KeyCode.A);
        settle();
        assertThat(selected()).containsExactly("R1", "R2", "R3", "R4");
        push(KeyCode.DELETE);
        settle();
        assertThat(project().components()).isEmpty();
        assertThat(project().nets()).isEmpty();
    }

    @Test
    void netLabelsConnectPinsWithoutWires() throws Exception {
        newProject("Labels");
        placePart("Resistor", 20, 20);
        placePart("Resistor", 20, 40);
        clickOn("#schematic-label");
        clickOn(pin("R1", 0));
        answer("VIN");
        clickOn(pin("R2", 0));
        answer("VIN");
        NetView vin = netOf("R1", 0);
        assertThat(vin.name()).isEqualTo("VIN");
        assertThat(vin.pinIds()).containsExactlyInAnyOrder(pinId("R1", 0), pinId("R2", 0));
        assertThat(vin.labels()).hasSize(2);
        assertThat(vin.wires()).isEmpty();

        clickOn("#schematic-select");
        clickOn(pin("R1", 0).add(-14, 0));
        push(KeyCode.DELETE);
        settle();
        assertThat(netOf("R1", 0).labels()).hasSize(1);
        assertThat(netOf("R1", 0).pinIds()).hasSize(2);
    }

    @Test
    void layoutAutorouteZoneFabricationSpiceAnd3d() throws Exception {
        newProject("Board");
        placePart("DC voltage source", 10, 20);
        placePart("Resistor", 30, 10);
        placePart("Resistor", 30, 30);
        placePart("Ground", 10, 45);
        wire("V1", 0, "R1", 0);
        wire("R1", 1, "R2", 0);
        wire("R2", 1, "GND1", 0);
        wire("V1", 1, "GND1", 0);

        interact(() -> lookup(".tab-pane").queryAs(TabPane.class).getSelectionModel().select(2));
        clickButton("Sync from schematic");
        settle();
        assertThat(project().board().placements()).isNotEmpty();
        clickOn("#pcb-autoroute");
        settle();
        assertThat(project().board().traces()).isNotEmpty();

        String ground = netOf("GND1", 0).name();
        interact(() -> lookup("#pcb-net").queryComboBox().getSelectionModel().select(ground));
        clickOn("#pcb-pour");
        settle();
        assertThat(project().board().zones()).as(() -> lookup(".dialog-pane").tryQuery()
                .map(n -> ((javafx.scene.control.DialogPane) n).getContentText()).orElse(status()))
                .singleElement().satisfies(zone -> {
            assertThat(zone.netId()).isEqualTo(netOf("GND1", 0).id());
            assertThat(zone.fill()).isNotEmpty();
        });
        clickOn("#pcb-delete-all-traces");
        clickButton("Yes");
        settle();
        assertThat(project().board().traces()).isEmpty();
        push(KeyCode.SHORTCUT, KeyCode.Z);
        settle();
        assertThat(project().board().traces()).isNotEmpty();

        Path fab = temp.resolve("fab");
        WorkbenchApplication.fileDialog = save -> fab.toFile();
        menuItem("Export fabrication files...");
        settle();
        try (var files = Files.list(fab)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .hasSize(9).anyMatch(n -> n.endsWith(".drl")).anyMatch(n -> n.endsWith("-BOM.csv"));
        }

        Path spice = temp.resolve("board.cir");
        WorkbenchApplication.fileDialog = save -> spice.toFile();
        menuItem("Export SPICE netlist...");
        settle();
        assertThat(Files.readString(spice)).contains("R1 ", "R2 ", ".op", ".end");

        interact(() -> lookup(".tab-pane").queryAs(TabPane.class).getSelectionModel().select(3));
        settle();
        assertThat(status()).doesNotContainIgnoringCase("error");

        menuItem("Import SPICE netlist...");
        if (lookup(button("Don't save")).tryQuery().isPresent()) {
            clickButton("Don't save");
        }
        settle();
        assertThat(project().components()).extracting(ComponentView::reference).contains("R1", "R2", "V1");
        assertThat(project().nets()).anySatisfy(n -> assertThat(n.name()).isEqualTo("GND"));
        interact(() -> lookup(".tab-pane").queryAs(TabPane.class).getSelectionModel().select(0));
    }

    // ---- Helpers ----

    private void menuItem(String text) throws Exception {
        var item = lookup(".menu-bar").queryAs(javafx.scene.control.MenuBar.class).getMenus().stream()
                .flatMap(m -> m.getItems().stream()).filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
        // runLater, not interact: the action may open a modal confirmation that the test answers afterwards
        javafx.application.Platform.runLater(item::fire);
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(300, TimeUnit.MILLISECONDS);
    }

    private static void settle() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitFor(60, TimeUnit.SECONDS, () -> fx(app::idle));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T fx(Callable<T> call) throws Exception {
        return WaitForAsyncUtils.asyncFx(call).get(30, TimeUnit.SECONDS);
    }

    private static Snapshot project() throws Exception {
        return fx(app::project);
    }

    private static ListView<DefinitionView> palette() {
        return new FxRobot().lookup("#schematic-palette").queryListView();
    }

    private String status() {
        return lookup("#workbench-status").queryAs(Label.class).getText();
    }

    private void newProject(String name) throws Exception {
        push(KeyCode.SHORTCUT, KeyCode.N);
        WaitForAsyncUtils.waitForFxEvents();
        if (lookup(button("Don't save")).tryQuery().isPresent()) {
            clickButton("Don't save");
        }
        answer(name);
        assertThat(project().name()).isEqualTo(name);
        assertThat(project().components()).isEmpty();
    }

    /** Types into the open text-input dialog and confirms it. */
    private void answer(String text) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        TextField field = lookup(".dialog-pane .text-field").queryAs(TextField.class);
        clickOn(field);
        push(KeyCode.SHORTCUT, KeyCode.A);
        write(text);
        push(KeyCode.ENTER);
        settle();
    }

    private static java.util.function.Predicate<Node> button(String text) {
        return node -> node instanceof Button b && text.equals(b.getText()) && b.isVisible();
    }

    private void clickButton(String text) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> lookup(button(text)).tryQuery().isPresent());
        clickOn(lookup(button(text)).queryAs(Button.class));
        settle();
    }

    private void placePart(String name, double xMm, double yMm) throws Exception {
        DefinitionView definition = palette().getItems().stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow();
        interact(() -> palette().scrollTo(definition));
        clickOn(lookup((Node n) -> n instanceof ListCell<?> cell && definition.equals(cell.getItem()))
                .queryAs(Node.class));
        clickOn(screen(xMm, yMm));
        settle();
    }

    private static Point2D screen(double xMm, double yMm) throws Exception {
        return fx(() -> app.schematicView().toScreen(xMm, yMm));
    }

    private static ComponentView component(String reference) throws Exception {
        return project().components().stream().filter(c -> c.reference().equals(reference)).findFirst().orElseThrow();
    }

    private static java.util.UUID pinId(String reference, int index) throws Exception {
        return component(reference).pins().get(index).id();
    }

    private static Point2D pin(String reference, int index) throws Exception {
        ComponentView c = component(reference);
        double[] at = Symbols.pin(c, c.pins().get(index));
        return screen(at[0], at[1]);
    }

    private void wire(String from, int fromPin, String to, int toPin) throws Exception {
        clickOn(pin(from, fromPin));
        clickOn(pin(to, toPin));
        settle();
    }

    private static NetView netOf(String reference, int index) throws Exception {
        var id = pinId(reference, index);
        return project().nets().stream().filter(n -> n.pinIds().contains(id)).findFirst().orElseThrow();
    }

    private static List<String> selected() throws Exception {
        return fx(() -> app.schematicView().selectedComponents().stream().map(ComponentView::reference).toList());
    }
}
