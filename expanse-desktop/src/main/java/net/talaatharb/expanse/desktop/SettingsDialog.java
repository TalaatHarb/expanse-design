package net.talaatharb.expanse.desktop;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/** File > Settings: where the server runs and how to reach it. */
final class SettingsDialog {
    private SettingsDialog() {
    }

    /** The edited values and whether to reconnect now (otherwise they apply at the next start). */
    record Result(DesktopSettings.Values values, boolean reconnect) {
    }

    static Optional<Result> show(Window owner, DesktopSettings.Values current, Path file, HealthMonitor health) {
        var mode = new ChoiceBox<String>();
        mode.getItems().setAll("local", "remote");
        mode.setValue(current.mode());
        var url = new TextField(current.serverUrl());
        var token = new PasswordField();
        token.setText(current.apiToken());
        token.setPromptText("generated automatically in local mode");
        var dataDir = new TextField(current.dataDir());
        dataDir.setPromptText(Path.of(System.getProperty("user.home"), ".expanse-design").toString());
        var browse = new Button("Browse...");
        browse.setOnAction(e -> {
            var chooser = new DirectoryChooser();
            File initial = dataDir.getText().isBlank() ? null : new File(dataDir.getText());
            if (initial != null && initial.isDirectory()) {
                chooser.setInitialDirectory(initial);
            }
            File chosen = chooser.showDialog(owner);
            if (chosen != null) {
                dataDir.setText(chosen.getAbsolutePath());
            }
        });
        mode.valueProperty().addListener((o, old, now) -> {
            if ("local".equals(now) && !url.getText().startsWith("http://127.0.0.1:")) {
                url.setText(DesktopSettings.DEFAULT_URL);
            }
        });
        var test = new Button("Test connection");
        var result = new Label();
        result.setWrapText(true);
        test.setOnAction(e -> {
            result.setText("Checking " + url.getText() + " ...");
            String target = url.getText();
            Thread.ofVirtual().start(() -> {
                var outcome = health.check(target);
                Platform.runLater(() -> result.setText(switch (outcome.status()) {
                    case UP -> "Server is up (" + target + ")";
                    case DEGRADED -> "Server answered but is not healthy: " + outcome.detail();
                    case UNREACHABLE -> "No server answered: " + outcome.detail()
                            + ("local".equals(mode.getValue()) ? ". In local mode the desktop starts one on connect." : "");
                }));
            });
        });

        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Mode"), mode);
        grid.addRow(1, new Label("Server URL"), url);
        grid.addRow(2, new Label("API token"), token);
        var dirRow = new HBox(6, dataDir, browse);
        HBox.setHgrow(dataDir, Priority.ALWAYS);
        grid.addRow(3, new Label("Data directory"), dirRow);
        GridPane.setHgrow(url, Priority.ALWAYS);
        url.setPrefColumnCount(36);
        var help = new Label("""
                Local: the desktop starts (or reuses) a server on this computer at http://127.0.0.1:<port>, storing \
                projects and the generated token in the data directory.
                Remote: connect to a shared server over HTTPS with its owner token (edit) or reader token (view).
                Saved to %s. Command-line options and environment variables take precedence.""".formatted(file));
        help.setWrapText(true);
        help.setMaxWidth(560);
        help.setStyle("-fx-text-fill: -fx-mid-text-color;");
        var content = new VBox(10, grid, new HBox(8, test), result, help);
        content.setPadding(new Insets(10));

        var dialog = new Dialog<Result>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Server connection");
        dialog.initOwner(owner);
        var reconnect = new ButtonType("Save and reconnect", ButtonBar.ButtonData.OK_DONE);
        var save = new ButtonType("Save", ButtonBar.ButtonData.APPLY);
        dialog.getDialogPane().getButtonTypes().setAll(reconnect, save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content);
        for (var type : new ButtonType[] {reconnect, save}) {
            dialog.getDialogPane().lookupButton(type).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                String problem = DesktopSettings.validate(values(mode, url, token, dataDir));
                if (problem != null) {
                    result.setText(problem);
                    e.consume();
                }
            });
        }
        dialog.setResultConverter(type -> type == reconnect || type == save
                ? new Result(values(mode, url, token, dataDir), type == reconnect) : null);
        return dialog.showAndWait();
    }

    private static DesktopSettings.Values values(ChoiceBox<String> mode, TextField url, PasswordField token,
                                                 TextField dataDir) {
        return new DesktopSettings.Values(mode.getValue(), url.getText(), token.getText(), dataDir.getText());
    }
}
