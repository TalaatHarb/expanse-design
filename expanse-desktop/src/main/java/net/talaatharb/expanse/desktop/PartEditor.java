package net.talaatharb.expanse.desktop;

import java.util.List;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import net.talaatharb.expanse.dto.ApiModels.*;

/**
 * Dialog for creating or editing a project part (symbol pins, parameters, footprint pads and 3D body). It produces a
 * {@link DefinePartOp} or a {@link DeletePartOp}; the server validates and applies it as one undoable revision.
 */
final class PartEditor {
    private static final String NEW = "<new part>";
    private static final ButtonType SAVE = new ButtonType("Save part", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType DELETE = new ButtonType("Delete part", ButtonBar.ButtonData.LEFT);

    private final TextField id = new TextField();
    private final TextField name = new TextField();
    private final TextField version = new TextField("1");
    private final ComboBox<String> kind = new ComboBox<>();
    private final TextArea pins = area("1, IN\n2, OUT\n3, GND");
    private final TextArea parameters = area("");
    private final TextField footprint = new TextField();
    private final TextArea pads = area("");
    private final TextField courtyardWidth = number(0);
    private final TextField courtyardHeight = number(0);
    private final ComboBox<String> shape = new ComboBox<>();
    private final TextField bodyWidth = number(0);
    private final TextField bodyDepth = number(0);
    private final TextField bodyHeight = number(2);
    private final ColorPicker color = new ColorPicker(Color.web("#404040"));
    private final Label error = new Label();

    private PartEditor() {
    }

    /** Shows the editor and returns the operation to apply, if any. */
    static Optional<Operation> show(Window owner, List<PartSpec> projectParts, List<DefinitionView> library,
                                    String partId) {
        return new PartEditor().open(owner, projectParts, library, partId);
    }

    private Optional<Operation> open(Window owner, List<PartSpec> projectParts, List<DefinitionView> library,
                                     String partId) {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle("Part editor");
        dialog.setHeaderText("Project parts are saved inside the project file and can be placed like library parts.");
        dialog.setResizable(true);

        var existing = new ComboBox<String>();
        existing.getItems().add(NEW);
        projectParts.forEach(p -> existing.getItems().add(p.id()));
        var template = new ComboBox<DefinitionView>();
        template.getItems().setAll(library);
        template.setPromptText("Copy pins and parameters from a library part...");
        template.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DefinitionView d) {
                return d == null ? "" : d.name() + " (" + d.kind() + ")";
            }

            @Override
            public DefinitionView fromString(String s) {
                return null;
            }
        });
        template.setMaxWidth(Double.MAX_VALUE);
        kind.getItems().add("generic");
        library.stream().map(DefinitionView::kind).distinct().sorted().forEach(kind.getItems()::add);
        kind.setValue("generic");
        kind.setTooltip(new Tooltip("generic parts are drawn as a box and ignored by simulation; any other kind must "
                + "have the pins and parameters of that kind's built-in model so it simulates like it"));
        shape.getItems().setAll("box", "cylinder", "axial", "none");
        shape.setValue("box");
        id.setPromptText("e.g. my-regulator (project. is added)");

        template.valueProperty().addListener((o, old, d) -> {
            if (d != null) {
                kind.setValue(d.kind());
                if (name.getText().isBlank()) {
                    name.setText("Custom " + d.name());
                }
                pins.setText(PartForms.pinsText(d.pins().stream()
                        .map(p -> new PinSpec(p.number(), p.name(), p.required())).toList()));
                parameters.setText(PartForms.parametersText(d.parameters()));
            }
        });

        var dipOrSip = new ComboBox<String>();
        dipOrSip.getItems().setAll("DIP", "SIP");
        dipOrSip.setValue("DIP");
        var pitch = number(2.54);
        var rowSpacing = number(7.62);
        var padSize = number(1.6);
        var drill = number(0.8);
        var generate = new Button("Generate pads from pins");
        generate.setOnAction(e -> guard(() -> {
            var pinList = PartForms.pins(pins.getText());
            var generated = dipOrSip.getValue().equals("DIP")
                    ? PartForms.dip(pinList, value(pitch), value(rowSpacing), value(padSize), value(drill))
                    : PartForms.sip(pinList, value(pitch), value(padSize), value(drill));
            pads.setText(PartForms.padsText(generated));
            double[] courtyard = PartForms.courtyard(generated);
            courtyardWidth.setText(PartForms.number(courtyard[0]));
            courtyardHeight.setText(PartForms.number(courtyard[1]));
            bodyWidth.setText(PartForms.number(Math.max(0, courtyard[0] - 1)));
            bodyDepth.setText(PartForms.number(Math.max(0, courtyard[1] - 1)));
            if (footprint.getText().isBlank()) {
                footprint.setText(dipOrSip.getValue() + "-" + pinList.size());
            }
        }));
        var generator = new HBox(6, dipOrSip, new Label("pitch"), pitch, new Label("row spacing"), rowSpacing,
                new Label("pad"), padSize, new Label("drill"), drill, generate);

        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int row = 0;
        grid.addRow(row++, new Label("Edit"), existing);
        grid.addRow(row++, new Label("ID"), id);
        grid.addRow(row++, new Label("Name"), name);
        grid.addRow(row++, new Label("Version"), version);
        grid.addRow(row++, new Label("Kind"), new HBox(6, kind, template));
        grid.addRow(row++, new Label("Pins"), labelled(pins, "number, name[, optional] \u2014 one per line"));
        grid.addRow(row++, new Label("Parameters"), labelled(parameters,
                "name, unit, default[, a|b|c] \u2014 one per line"));
        grid.addRow(row++, new Label("Footprint"), footprint);
        grid.addRow(row++, new Label("Pad generator"), generator);
        grid.addRow(row++, new Label("Pads"), labelled(pads,
                "number, x, y, width, height, drill (mm; drill 0 = SMD) \u2014 empty for a schematic-only part"));
        grid.addRow(row++, new Label("Courtyard (mm)"), new HBox(6, new Label("W"), courtyardWidth, new Label("H"),
                courtyardHeight));
        grid.addRow(row++, new Label("3D body"), new HBox(6, shape, new Label("W"), bodyWidth, new Label("D"),
                bodyDepth, new Label("H"), bodyHeight, color));
        error.setTextFill(Color.FIREBRICK);
        error.setWrapText(true);
        grid.add(error, 0, row, 2, 1);
        footprint.setPromptText("defaults to the part name");
        GridPane.setHgrow(id, Priority.ALWAYS);
        var scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefSize(900, 760);
        dialog.getDialogPane().getButtonTypes().setAll(SAVE, ButtonType.CANCEL);

        existing.valueProperty().addListener((o, old, now) -> {
            var part = projectParts.stream().filter(p -> p.id().equals(now)).findFirst().orElse(null);
            load(part);
            id.setDisable(part != null);
            dialog.getDialogPane().getButtonTypes().setAll(part == null ? List.of(SAVE, ButtonType.CANCEL)
                    : List.of(DELETE, SAVE, ButtonType.CANCEL));
            installValidation(dialog);
        });
        existing.setValue(existing.getItems().contains(partId) ? partId : NEW);
        installValidation(dialog);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        if (result.get() == DELETE) {
            var confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + existing.getValue()
                    + "? Parts that are still placed cannot be deleted.", ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(owner);
            return confirm.showAndWait().filter(b -> b == ButtonType.OK)
                    .map(b -> new DeletePartOp(existing.getValue()));
        }
        return Optional.of(new DefinePartOp(spec()));
    }

    private void installValidation(Dialog<ButtonType> dialog) {
        var save = dialog.getDialogPane().lookupButton(SAVE);
        if (save != null && save.getProperties().putIfAbsent("validated", true) == null) {
            save.addEventFilter(ActionEvent.ACTION, e -> {
                if (!guard(this::spec)) {
                    e.consume();
                }
            });
        }
    }

    private void load(PartSpec part) {
        error.setText("");
        if (part == null) {
            id.clear();
            name.clear();
            version.setText("1");
            kind.setValue("generic");
            pins.setText("1, IN\n2, OUT\n3, GND");
            parameters.clear();
            footprint.clear();
            pads.clear();
            courtyardWidth.setText("0");
            courtyardHeight.setText("0");
            shape.setValue("box");
            bodyWidth.setText("0");
            bodyDepth.setText("0");
            bodyHeight.setText("2");
            color.setValue(Color.web("#404040"));
            return;
        }
        id.setText(part.id());
        name.setText(part.name());
        version.setText(part.version());
        kind.setValue(part.kind());
        pins.setText(PartForms.pinsText(part.pins()));
        parameters.setText(PartForms.parametersText(part.parameters()));
        footprint.setText(part.footprintName());
        pads.setText(PartForms.padsText(part.pads()));
        courtyardWidth.setText(PartForms.number(part.courtyardWidthMm()));
        courtyardHeight.setText(PartForms.number(part.courtyardHeightMm()));
        if (part.body() != null) {
            shape.setValue(part.body().shape());
            bodyWidth.setText(PartForms.number(part.body().widthMm()));
            bodyDepth.setText(PartForms.number(part.body().depthMm()));
            bodyHeight.setText(PartForms.number(part.body().heightMm()));
            color.setValue(Color.web(part.body().color()));
        }
    }

    private PartSpec spec() {
        if (id.getText().isBlank() || name.getText().isBlank()) {
            throw new IllegalArgumentException("ID and name are required");
        }
        var padList = PartForms.pads(pads.getText());
        Color c = color.getValue();
        String hex = String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
        return new PartSpec(id.getText().strip(), version.getText().strip(), name.getText().strip(), kind.getValue(),
                PartForms.pins(pins.getText()), PartForms.parameters(parameters.getText()),
                footprint.getText().strip(), padList, value(courtyardWidth), value(courtyardHeight),
                new BodySpec(padList.isEmpty() ? "none" : shape.getValue(), value(bodyWidth), value(bodyDepth),
                        value(bodyHeight), hex));
    }

    private boolean guard(Runnable action) {
        try {
            action.run();
            error.setText("");
            return true;
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            return false;
        }
    }

    private static double value(TextField field) {
        try {
            return Double.parseDouble(field.getText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + field.getText() + "' is not a number");
        }
    }

    private static TextField number(double value) {
        var field = new TextField(PartForms.number(value));
        field.setPrefColumnCount(5);
        return field;
    }

    private static TextArea area(String text) {
        var area = new TextArea(text);
        area.setPrefRowCount(5);
        area.setStyle("-fx-font-family: monospace");
        return area;
    }

    private static VBox labelled(TextArea area, String help) {
        var label = new Label(help);
        label.setTextFill(Color.GRAY);
        return new VBox(2, area, label);
    }
}
