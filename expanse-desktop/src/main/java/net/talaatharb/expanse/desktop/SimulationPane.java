package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import net.talaatharb.expanse.dto.ApiModels.*;

/** Runs DC operating point, transient and logic analyses and shows tables, waveforms and timing diagrams. */
final class SimulationPane {
    private static final int MAX_CHART_POINTS = 600;
    private final Workbench workbench;
    private final BorderPane root = new BorderPane();
    private final ComboBox<String> analysis = new ComboBox<>();
    private final TextField stopTime = new TextField("10m");
    private final TextField timeStep = new TextField("10u");
    private final CheckBox zeroInitial = new CheckBox("Start from zero (capacitors discharged)");
    private final TextArea switchEvents = new TextArea();
    private final TableView<String[]> table = new TableView<>();
    private final LineChart<Number, Number> chart = new LineChart<>(new NumberAxis(), new NumberAxis());
    private final VBox seriesChoices = new VBox(3);
    private final Label summary = new Label("Run an analysis to see results.");
    private final TimingDiagram timing = new TimingDiagram();
    private final SplitPane results;
    private SimulationReport report;
    private double requestedStopS;

    SimulationPane(Workbench workbench) {
        this.workbench = workbench;
        analysis.getItems().setAll("op", "transient", "logic");
        analysis.setValue("transient");
        analysis.valueProperty().addListener((o, old, now) -> updateEnabled());
        zeroInitial.setSelected(true);
        switchEvents.setPromptText("One event per line: <switch> <time> <open|closed>\ne.g. SW1 2m closed");
        switchEvents.setPrefRowCount(4);
        var run = new Button("Run simulation");
        run.setDefaultButton(true);
        run.setMaxWidth(Double.MAX_VALUE);
        run.setOnAction(e -> run());
        var form = new GridPane();
        form.setHgap(6);
        form.setVgap(6);
        form.addRow(0, new Label("Analysis"), analysis);
        form.addRow(1, new Label("Stop time (s)"), stopTime);
        form.addRow(2, new Label("Time step (s)"), timeStep);
        var controls = new VBox(8, form, zeroInitial, new Label("Switch events"), switchEvents, run,
                new Separator(), new Label("Waveforms"), new ScrollPane(seriesChoices));
        controls.setPadding(new Insets(8));
        controls.setPrefWidth(280);

        var quantity = new TableColumn<String[], String>("Quantity");
        quantity.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[0]));
        quantity.setPrefWidth(180);
        var value = new TableColumn<String[], String>("Value");
        value.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[1]));
        value.setPrefWidth(140);
        table.getColumns().setAll(List.of(quantity, value));
        table.setPlaceholder(new Label("No results yet"));
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.getXAxis().setLabel("time (s)");
        chart.getYAxis().setLabel("V / A");
        results = new SplitPane(chart, table);
        results.setOrientation(Orientation.HORIZONTAL);
        results.setDividerPositions(0.7);
        summary.setWrapText(true);
        summary.setPadding(new Insets(6));
        root.setLeft(controls);
        root.setCenter(results);
        root.setBottom(summary);
        VBox.setVgrow(switchEvents, Priority.SOMETIMES);
        updateEnabled();
    }

    Node node() {
        return root;
    }

    void show(Snapshot snapshot) {
        if (snapshot == null || report != null && !report.projectId().equals(snapshot.id())) {
            report = null;
            table.getItems().clear();
            chart.getData().clear();
            seriesChoices.getChildren().clear();
            summary.setText("Run an analysis to see results.");
        } else if (report != null && report.revision() != snapshot.revision()) {
            summary.setText("Results are from revision " + report.revision() + "; the design is now at revision "
                    + snapshot.revision() + ". Run again to refresh.");
        }
    }

    private void updateEnabled() {
        boolean transientAnalysis = "transient".equals(analysis.getValue());
        boolean timed = transientAnalysis || "logic".equals(analysis.getValue());
        stopTime.setDisable(!timed);
        timeStep.setDisable(!transientAnalysis);
        zeroInitial.setDisable(!transientAnalysis);
        switchEvents.setDisable(!timed);
    }

    /** Presets the form from {@code analysis;stopTime;event|event}, e.g. {@code logic;3m;SW1 1.2m closed}. */
    void configure(String preset) {
        String[] parts = preset.split(";", -1);
        analysis.setValue(parts[0].strip());
        if (parts.length > 1 && !parts[1].isBlank()) {
            stopTime.setText(parts[1].strip());
        }
        if (parts.length > 2) {
            switchEvents.setText(parts[2].replace("|", "\n"));
        }
    }

    void run() {
        Snapshot project = workbench.project();
        if (project == null) {
            return;
        }
        SimulationRequest request;
        try {
            request = request();
        } catch (IllegalArgumentException e) {
            workbench.error(e.getMessage());
            return;
        }
        requestedStopS = request.stopTimeS() == null ? 0 : request.stopTimeS();
        workbench.status("Simulating " + request.analysis() + "...");
        workbench.run(() -> workbench.api().simulate(project.id(), request), this::showReport);
    }

    SimulationRequest request() {
        if ("op".equals(analysis.getValue())) {
            return new SimulationRequest("op", null, null, null, null);
        }
        var events = new ArrayList<SwitchEventView>();
        int line = 0;
        for (String raw : switchEvents.getText().split("\\R")) {
            line++;
            if (raw.isBlank()) {
                continue;
            }
            String[] parts = raw.strip().split("\\s+");
            if (parts.length != 3 || !(parts[2].equals("open") || parts[2].equals("closed"))) {
                throw new IllegalArgumentException("Switch event line " + line
                        + " must be '<switch> <time> <open|closed>'");
            }
            events.add(new SwitchEventView(parts[0], parseSi(parts[1], "event time"), parts[2]));
        }
        if ("logic".equals(analysis.getValue())) {
            return new SimulationRequest("logic", parseSi(stopTime.getText(), "stop time"), null, null, events);
        }
        return new SimulationRequest("transient", parseSi(stopTime.getText(), "stop time"),
                parseSi(timeStep.getText(), "time step"), zeroInitial.isSelected(), events);
    }

    private void showReport(SimulationReport result) {
        report = result;
        var rows = new ArrayList<String[]>();
        new TreeMap<>(result.nodeVoltages()).forEach((net, v) -> rows.add(new String[]{"V(" + net + ")", format(v, "V")}));
        new TreeMap<>(result.componentPower()).forEach((ref, p) -> rows.add(new String[]{"P(" + ref + ")", format(p, "W")}));
        new TreeMap<>(result.pinCurrents()).forEach((ref, pins) -> new TreeMap<>(pins).forEach((pin, i) ->
                rows.add(new String[]{"I(" + ref + "." + pin + ")", format(i, "A")})));
        if (result.logicLevels() != null) {
            new TreeMap<>(result.logicLevels()).forEach((net, level) -> rows.add(new String[]{"L(" + net + ")",
                    level}));
        }
        table.getItems().setAll(rows);
        seriesChoices.getChildren().clear();
        chart.getData().clear();
        boolean logic = "logic".equals(result.analysis());
        results.getItems().set(0, logic ? timing.node() : chart);
        if (logic) {
            int[] count = {0};
            new TreeMap<>(result.logicSeries()).keySet().forEach(name -> {
                var box = new CheckBox("L(" + name + ")");
                box.setUserData(new String[]{"L", name});
                box.setSelected(count[0]++ < 12);
                box.selectedProperty().addListener(o -> redrawTiming());
                seriesChoices.getChildren().add(box);
            });
            redrawTiming();
        } else if ("transient".equals(result.analysis())) {
            addChoices(result.voltageSeries(), "V", true);
            addChoices(result.currentSeries(), "I", false);
            redrawChart();
        }
        var notes = new ArrayList<String>();
        notes.add(result.analysis() + " at revision " + result.revision() + " \u2022 " + result.engine()
                + " \u2022 " + result.models());
        notes.addAll(result.warnings());
        result.diagnostics().stream().filter(d -> d.severity().equals("ERROR"))
                .forEach(d -> notes.add(d.code() + ": " + d.message()));
        summary.setText(String.join("\n", notes));
        workbench.status("Simulation finished: " + rows.size() + " values");
    }

    private void addChoices(Map<String, List<Double>> series, String prefix, boolean selected) {
        new TreeMap<>(series).keySet().forEach(name -> {
            var box = new CheckBox(prefix + "(" + name + ")");
            box.setUserData(new String[]{prefix, name});
            box.setSelected(selected);
            box.selectedProperty().addListener(o -> redrawChart());
            seriesChoices.getChildren().add(box);
        });
    }

    private void redrawTiming() {
        var lanes = new java.util.LinkedHashMap<String, List<LogicTransitionView>>();
        for (Node node : seriesChoices.getChildren()) {
            if (node instanceof CheckBox box && box.isSelected() && report != null && report.logicSeries() != null) {
                String name = ((String[]) box.getUserData())[1];
                lanes.put(name, report.logicSeries().get(name));
            }
        }
        double last = report == null || report.logicSeries() == null ? 0 : report.logicSeries().values().stream()
                .filter(l -> !l.isEmpty()).mapToDouble(l -> l.getLast().timeS()).max().orElse(0);
        timing.show(lanes, Math.max(requestedStopS, last));
    }

    private void redrawChart() {
        chart.getData().clear();
        if (report == null || report.time() == null) {
            return;
        }
        List<Double> time = report.time();
        int stride = Math.max(1, (int) Math.ceil(time.size() / (double) MAX_CHART_POINTS));
        for (Node node : seriesChoices.getChildren()) {
            if (!(node instanceof CheckBox box) || !box.isSelected()) {
                continue;
            }
            String[] key = (String[]) box.getUserData();
            List<Double> values = (key[0].equals("V") ? report.voltageSeries() : report.currentSeries()).get(key[1]);
            var series = new XYChart.Series<Number, Number>();
            series.setName(box.getText());
            for (int i = 0; i < time.size(); i += stride) {
                series.getData().add(new XYChart.Data<>(time.get(i), values.get(i)));
            }
            if ((time.size() - 1) % stride != 0) {
                series.getData().add(new XYChart.Data<>(time.getLast(), values.getLast()));
            }
            chart.getData().add(series);
        }
    }

    /** Parses numbers with SI prefixes (f p n u \u00b5 m k M/meg G), e.g. {@code 10m} or {@code 2.2u}. */
    static double parseSi(String text, String what) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Enter a value for " + what);
        }
        double multiplier = 1;
        if (value.length() > 1 && value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith("meg")) {
            multiplier = 1e6;
            value = value.substring(0, value.length() - 3);
        } else {
            char last = value.charAt(value.length() - 1);
            double prefix = switch (last) {
                case 'f' -> 1e-15;
                case 'p' -> 1e-12;
                case 'n' -> 1e-9;
                case 'u', '\u00b5' -> 1e-6;
                case 'm' -> 1e-3;
                case 'k', 'K' -> 1e3;
                case 'M' -> 1e6;
                case 'G' -> 1e9;
                default -> 0;
            };
            if (prefix != 0) {
                multiplier = prefix;
                value = value.substring(0, value.length() - 1);
            }
        }
        try {
            double parsed = Double.parseDouble(value) * multiplier;
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + what + ": '" + text + "'");
        }
    }

    /** Engineering notation, e.g. {@code 4.545 mA}. */
    static String format(double value, String unit) {
        if (value == 0 || !Double.isFinite(value)) {
            return (value == 0 ? "0" : String.valueOf(value)) + " " + unit;
        }
        String[] prefixes = {"f", "p", "n", "\u00b5", "m", "", "k", "M", "G"};
        int exponent = (int) Math.floor(Math.log10(Math.abs(value)) / 3);
        exponent = Math.max(-5, Math.min(3, exponent));
        double scaled = value / Math.pow(1000, exponent);
        return String.format(Locale.ROOT, "%.4g %s%s", scaled, prefixes[exponent + 5], unit);
    }
}
