package net.talaatharb.expanse.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import net.talaatharb.expanse.dto.ApiModels.LogicTransitionView;

/** Timing diagram for logic analysis results: one lane per net, high/low traces, X shaded red, Z dashed mid-line. */
final class TimingDiagram {
    private static final double LABEL_WIDTH = 110;
    private static final double LANE_HEIGHT = 34;
    private static final double TOP = 24;
    private final Canvas canvas = new Canvas();
    private final Pane root = new Pane(canvas);
    private Map<String, List<LogicTransitionView>> lanes = Map.of();
    private double stopTimeS = 1;

    /** A constant-level stretch of a waveform. */
    record Segment(double fromS, double toS, char level) {
    }

    TimingDiagram() {
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        canvas.widthProperty().addListener(o -> redraw());
        canvas.heightProperty().addListener(o -> redraw());
        root.setMinSize(0, 0);
    }

    Pane node() {
        return root;
    }

    void show(Map<String, List<LogicTransitionView>> lanes, double stopTimeS) {
        this.lanes = lanes;
        this.stopTimeS = stopTimeS > 0 ? stopTimeS : 1;
        redraw();
    }

    /** Splits transitions into segments that end at the next transition or at the stop time. */
    static List<Segment> segments(List<LogicTransitionView> transitions, double stopTimeS) {
        List<Segment> result = new ArrayList<>();
        for (int i = 0; i < transitions.size(); i++) {
            double from = transitions.get(i).timeS();
            double to = i + 1 < transitions.size() ? transitions.get(i + 1).timeS() : Math.max(stopTimeS, from);
            if (from > stopTimeS) {
                break;
            }
            String level = transitions.get(i).level();
            result.add(new Segment(from, Math.min(to, stopTimeS), level.isEmpty() ? 'X' : level.charAt(0)));
        }
        return result;
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, width, height);
        if (lanes.isEmpty()) {
            g.setFill(Color.GRAY);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("Select nets to show their timing", width / 2, height / 2);
            return;
        }
        double plot = Math.max(10, width - LABEL_WIDTH - 12);
        g.setFont(Font.font(11));
        g.setTextBaseline(VPos.CENTER);
        g.setTextAlign(TextAlignment.CENTER);
        g.setStroke(Color.GAINSBORO);
        g.setFill(Color.DIMGRAY);
        for (int tick = 0; tick <= 5; tick++) {
            double x = LABEL_WIDTH + plot * tick / 5;
            g.strokeLine(x, TOP - 4, x, height);
            g.fillText(SimulationPane.format(stopTimeS * tick / 5, "s"), x, TOP / 2);
        }
        int lane = 0;
        for (var entry : lanes.entrySet()) {
            double y = TOP + lane++ * LANE_HEIGHT;
            double high = y + 6;
            double low = y + LANE_HEIGHT - 8;
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(Color.BLACK);
            g.fillText(entry.getKey(), LABEL_WIDTH - 8, (high + low) / 2);
            g.setLineWidth(1.5);
            Double previousY = null;
            for (Segment segment : segments(entry.getValue(), stopTimeS)) {
                double x1 = LABEL_WIDTH + plot * segment.fromS() / stopTimeS;
                double x2 = LABEL_WIDTH + plot * segment.toS() / stopTimeS;
                double levelY = switch (segment.level()) {
                    case '1' -> high;
                    case '0' -> low;
                    default -> (high + low) / 2;
                };
                if (segment.level() == 'X') {
                    g.setFill(Color.rgb(220, 50, 47, 0.35));
                    g.fillRect(x1, high, Math.max(1, x2 - x1), low - high);
                }
                g.setStroke(segment.level() == 'X' ? Color.rgb(220, 50, 47) : segment.level() == 'Z'
                        ? Color.DARKORANGE : Color.rgb(38, 139, 210));
                if (segment.level() == 'Z') {
                    g.setLineDashes(4, 3);
                } else {
                    g.setLineDashes((double[]) null);
                }
                if (previousY != null) {
                    g.strokeLine(x1, previousY, x1, levelY);
                }
                g.strokeLine(x1, levelY, x2, levelY);
                previousY = levelY;
            }
            g.setLineDashes((double[]) null);
            if (y + LANE_HEIGHT > height) {
                break;
            }
        }
    }
}
