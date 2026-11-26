package net.talaatharb.expanse.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The shared client/server contract. Units are millimetres, seconds, volts, amperes and watts. */
public final class ApiModels {
    private ApiModels() {
    }

    public record CreateProject(@NotBlank @Size(max = 200) String name) {
    }

    /** Legacy single-operation request; equivalent to a change set with one {@link PlaceOp}. */
    public record PlaceComponent(@PositiveOrZero long expectedRevision, @NotNull UUID sheetId,
                                 @NotBlank String definitionId, @NotBlank @Size(max = 200) String reference,
                                 double xMm, double yMm) {
    }

    public record ConnectPins(@PositiveOrZero long expectedRevision, @NotBlank @Size(max = 200) String name,
                              @NotNull @Size(min = 2, max = 1000) List<@NotNull UUID> pinIds) {
    }

    public record Document(@NotBlank @Size(max = 8_388_608) String json) {
    }

    public record ReplaceDocument(@PositiveOrZero long expectedRevision, @NotBlank @Size(max = 8_388_608) String json) {
    }

    public record Summary(UUID id, String name, long revision, int components, int nets) {
    }

    public record SheetView(UUID id, String name) {
    }

    public record PinView(UUID id, String number, String name, boolean required) {
    }

    public record ParameterView(String name, String unit, String defaultValue, List<String> allowedValues) {
    }

    public record ComponentView(UUID id, UUID sheetId, String reference, String definitionId,
                                String definitionVersion, String name, String kind, double xMm, double yMm,
                                int rotation, Map<String, String> properties, List<ParameterView> parameters,
                                boolean physical, List<PinView> pins) {
    }

    public record NetView(UUID id, String name, List<UUID> pinIds, List<WireView> wires, List<NetLabelView> labels) {
        public NetView(UUID id, String name, List<UUID> pinIds, List<WireView> wires) {
            this(id, name, pinIds, wires, List.of());
        }
    }

    /** A net-name label on a pin ({@code pinId}) or at a free {@code point}; exactly one is set. */
    public record NetLabelView(UUID id, UUID pinId, PointView point) {
    }

    public record PointView(double xMm, double yMm) {
    }

    /** Attached ends reference pins; a null pin means the first/last point is a free end. Other points are bends. */
    public record WireView(UUID id, UUID fromPinId, UUID toPinId, List<PointView> points) {
    }

    public record PlacementView(UUID componentId, String reference, double xMm, double yMm, int rotation,
                                double courtyardWidthMm, double courtyardHeightMm) {
    }

    public record TraceView(UUID id, UUID netId, String layer, double widthMm, List<PointView> points) {
    }

    public record PadView(UUID componentId, String reference, UUID pinId, String pinNumber, UUID netId,
                          double xMm, double yMm, double widthMm, double heightMm, double drillMm) {
    }

    public record BoardView(double widthMm, double heightMm, double thicknessMm, double clearanceMm,
                            List<PlacementView> placements, List<TraceView> traces, List<PadView> pads) {
    }

    public record Snapshot(UUID id, String name, long revision, List<SheetView> sheets,
                           List<ComponentView> components, List<NetView> nets, BoardView board,
                           List<PartSpec> parts) {
    }

    // ---- Custom (project-local) parts ----

    public record PinSpec(@NotBlank @Size(max = 200) String number, @NotBlank @Size(max = 200) String name,
                          boolean required) {
    }

    public record PadSpec(@NotBlank @Size(max = 200) String number, double xMm, double yMm, double widthMm,
                          double heightMm, double drillMm) {
    }

    /** Shape is box, cylinder, axial or none; colour is #RRGGBB. */
    public record BodySpec(@NotBlank String shape, double widthMm, double depthMm, double heightMm,
                           @NotBlank String color) {
    }

    /**
     * A project-local part definition. IDs start with {@code project.}; a bare name is prefixed automatically.
     * Kind defaults to {@code generic} (schematic box symbol, no simulation model); a built-in kind may be used when
     * the pins and parameters match what its simulation model expects. Without pads the part is schematic-only.
     * Version defaults to 1, footprint name to the part name and body to a grey box over the courtyard.
     */
    public record PartSpec(@NotBlank @Size(max = 200) String id, String version, @NotBlank @Size(max = 200) String name,
                           String kind, @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid PinSpec> pins,
                           @Size(max = 100) List<@NotNull ParameterView> parameters, String footprintName,
                           @Size(max = 500) List<@NotNull @Valid PadSpec> pads, double courtyardWidthMm,
                           double courtyardHeightMm, @Valid BodySpec body) {
    }

    public record DefinitionView(String id, String version, String name, String kind, List<PinView> pins,
                                 List<ParameterView> parameters, boolean schematicReady, boolean pcbReady,
                                 boolean simulationReady) {
    }

    public record Diagnostic(String code, String severity, String message, List<UUID> affectedIds) {
    }

    public record ValidationReport(UUID projectId, long revision, List<Diagnostic> diagnostics) {
    }

    public record Capability(String id, boolean available, String reason) {
    }

    public record ApiError(String code, String message, Long currentRevision) {
    }

    // ---- Transactional change sets (shared by desktop, REST and MCP) ----

    /**
     * Component, pin, net and trace selectors accept either a UUID or a human-readable form:
     * components by reference ({@code R1}), pins as {@code REF.PIN} using the pin number or name
     * ({@code Q1.B}), nets by name.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = PlaceOp.class, name = "place_component"),
            @JsonSubTypes.Type(value = MoveOp.class, name = "move_component"),
            @JsonSubTypes.Type(value = SetPropertiesOp.class, name = "set_properties"),
            @JsonSubTypes.Type(value = SetReferenceOp.class, name = "set_reference"),
            @JsonSubTypes.Type(value = DeleteComponentOp.class, name = "delete_component"),
            @JsonSubTypes.Type(value = ConnectOp.class, name = "connect_pins"),
            @JsonSubTypes.Type(value = DisconnectOp.class, name = "disconnect_pin"),
            @JsonSubTypes.Type(value = RenameNetOp.class, name = "rename_net"),
            @JsonSubTypes.Type(value = RenameProjectOp.class, name = "rename_project"),
            @JsonSubTypes.Type(value = SyncBoardOp.class, name = "sync_board"),
            @JsonSubTypes.Type(value = PlaceFootprintOp.class, name = "place_footprint"),
            @JsonSubTypes.Type(value = UnplaceFootprintOp.class, name = "unplace_footprint"),
            @JsonSubTypes.Type(value = SetBoardOp.class, name = "set_board"),
            @JsonSubTypes.Type(value = RouteOp.class, name = "route"),
            @JsonSubTypes.Type(value = AddTraceOp.class, name = "add_trace"),
            @JsonSubTypes.Type(value = DeleteTraceOp.class, name = "delete_trace"),
            @JsonSubTypes.Type(value = DrawWireOp.class, name = "draw_wire"),
            @JsonSubTypes.Type(value = DeleteWireOp.class, name = "delete_wire"),
            @JsonSubTypes.Type(value = PlaceNetLabelOp.class, name = "place_net_label"),
            @JsonSubTypes.Type(value = DeleteNetLabelOp.class, name = "delete_net_label"),
            @JsonSubTypes.Type(value = DefinePartOp.class, name = "define_part"),
            @JsonSubTypes.Type(value = DeletePartOp.class, name = "delete_part")})
    public sealed interface Operation permits PlaceOp, MoveOp, SetPropertiesOp, SetReferenceOp, DeleteComponentOp,
            ConnectOp, DisconnectOp, RenameNetOp, RenameProjectOp, SyncBoardOp, PlaceFootprintOp,
            UnplaceFootprintOp, SetBoardOp, RouteOp, AddTraceOp, DeleteTraceOp, DrawWireOp, DeleteWireOp,
            PlaceNetLabelOp, DeleteNetLabelOp, DefinePartOp, DeletePartOp {
        /** True when the operation removes user data and may need explicit approval. */
        default boolean destructive() {
            return false;
        }
    }

    /** Rotation defaults to 0, properties to the definition defaults and sheet to the first sheet. */
    public record PlaceOp(@NotBlank String definitionId, @NotBlank @Size(max = 200) String reference,
                          double xMm, double yMm, Integer rotation, Map<String, String> properties,
                          UUID sheetId) implements Operation {
    }

    /** Omitted rotation keeps the current rotation. */
    public record MoveOp(@NotBlank String component, double xMm, double yMm, Integer rotation) implements Operation {
    }

    public record SetPropertiesOp(@NotBlank String component, @NotNull Map<String, String> properties)
            implements Operation {
    }

    public record SetReferenceOp(@NotBlank String component, @NotBlank @Size(max = 200) String reference)
            implements Operation {
    }

    public record DeleteComponentOp(@NotBlank String component) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    /** Name may be omitted; an existing net keeps its name, a new one is named after the first pin. */
    public record ConnectOp(String name, @NotNull @Size(min = 2, max = 1000) List<@NotBlank String> pins)
            implements Operation {
    }

    public record DisconnectOp(@NotBlank String pin) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    public record RenameNetOp(@NotBlank String net, @NotBlank @Size(max = 200) String name) implements Operation {
    }

    public record RenameProjectOp(@NotBlank @Size(max = 200) String name) implements Operation {
    }

    /** Places every physical component that is not yet on the board. */
    public record SyncBoardOp() implements Operation {
    }

    public record PlaceFootprintOp(@NotBlank String component, double xMm, double yMm, Integer rotation)
            implements Operation {
    }

    public record UnplaceFootprintOp(@NotBlank String component) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    /** Omitted values keep the current board settings. */
    public record SetBoardOp(Double widthMm, Double heightMm, Double thicknessMm, Double clearanceMm)
            implements Operation {
    }

    /** Orthogonal route between two placed pads of the same net; layer defaults to top, width to 0.4 mm. */
    public record RouteOp(@NotBlank String from, @NotBlank String to, String layer, Double widthMm)
            implements Operation {
    }

    public record AddTraceOp(@NotBlank String net, String layer, Double widthMm,
                             @NotNull @Size(min = 2, max = 1000) List<@NotNull PointView> points) implements Operation {
    }

    public record DeleteTraceOp(@NotNull UUID traceId) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    /**
     * Draws a schematic wire. {@code from}/{@code to} are optional pin selectors; a missing end is free and is the
     * first/last of {@code points} (for example a T on an existing wire of {@code net}). The pins and {@code net} are
     * connected as with connect_pins, so drawing between two pins also creates or merges their nets. Name applies
     * only when a new net is created.
     */
    public record DrawWireOp(String from, String to, String net, @Size(max = 1000) List<@NotNull PointView> points,
                             String name) implements Operation {
    }

    /** Removes wire graphics only; connectivity is unchanged. */
    public record DeleteWireOp(@NotNull UUID wireId) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    /**
     * Labels a pin ({@code pin}) or a point ({@code xMm}/{@code yMm}) on {@code net} with {@code name}. The labelled
     * net is renamed to {@code name}, or merged into an existing net of that name, so equal labels connect pins
     * without wires. An unconnected pin becomes a one-pin labelled net.
     */
    public record PlaceNetLabelOp(@NotBlank @Size(max = 200) String name, String pin, String net, Double xMm,
                                  Double yMm) implements Operation {
    }

    /** Removes a label; a net left with one pin and no labels is removed. */
    public record DeleteNetLabelOp(@NotNull UUID labelId) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    /** Creates or replaces a project part; existing instances are re-pinned to the new definition. */
    public record DefinePartOp(@NotNull @Valid PartSpec part) implements Operation {
    }

    /** Removes an unused project part. Fails with PART_IN_USE while instances exist. */
    public record DeletePartOp(@NotBlank String partId) implements Operation {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    public record ChangeSet(@PositiveOrZero long expectedRevision,
                            @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Operation> operations) {
    }

    public record CreatedObject(String kind, String label, UUID id) {
    }

    /**
     * Preview returns the would-be snapshot and diagnostics without persisting; apply persists all operations as a
     * single revision or none of them. IDs created in a preview are illustrative and differ from those of apply.
     */
    public record ChangeResult(UUID projectId, long baseRevision, long revision, boolean applied,
                               List<String> changes, List<CreatedObject> created, List<Diagnostic> diagnostics,
                               Snapshot snapshot) {
    }

    // ---- Undo / redo ----

    public record HistoryStep(@PositiveOrZero long expectedRevision) {
    }

    /**
     * Undo and redo restore an earlier document as a new revision, so they are ordinary, revision-checked edits
     * shared by every client of the project. {@code undoRevision}/{@code redoRevision} name the revision whose
     * content would be restored.
     */
    public record HistoryView(UUID projectId, long revision, boolean canUndo, boolean canRedo, Long undoRevision,
                              Long redoRevision) {
    }

    // ---- Simulation ----

    public record SwitchEventView(@NotBlank String reference, double timeS, @NotBlank String state) {
    }

    /**
     * analysis is {@code op}, {@code transient} (requires stopTimeS and timeStepS) or {@code logic} (event-driven
     * digital simulation; requires stopTimeS, ignores timeStepS and zeroInitialConditions).
     */
    public record SimulationRequest(@NotBlank String analysis, Double stopTimeS, Double timeStepS,
                                    Boolean zeroInitialConditions,
                                    @Size(max = 100) List<@NotNull @Valid SwitchEventView> switchEvents) {
    }

    /** A net level change; level is {@code 0}, {@code 1}, {@code X} (unknown) or {@code Z} (undriven). */
    public record LogicTransitionView(double timeS, String level) {
    }

    /**
     * Analog analyses fill voltages, currents and series; the logic analysis fills {@code logicLevels} (final level
     * per net) and {@code logicSeries} (transitions per net, starting at t = 0) and leaves the analog fields empty.
     */
    public record SimulationReport(UUID projectId, long revision, String analysis, String engine, String models,
                                   Map<String, Double> nodeVoltages, Map<String, Map<String, Double>> pinCurrents,
                                   Map<String, Double> componentPower, List<Double> time,
                                   Map<String, List<Double>> voltageSeries, Map<String, List<Double>> currentSeries,
                                   Map<String, String> logicLevels,
                                   Map<String, List<LogicTransitionView>> logicSeries,
                                   List<String> warnings, List<Diagnostic> diagnostics) {
    }

    // ---- PCB and 3D assembly ----

    public record AirwireView(UUID netId, String netName, UUID fromPinId, String fromLabel, double fromXMm,
                              double fromYMm, UUID toPinId, String toLabel, double toXMm, double toYMm) {
    }

    public record BoardAnalysis(UUID projectId, long revision, List<PadView> pads, List<AirwireView> airwires,
                                List<Diagnostic> findings) {
    }

    /** Z is up from the top copper surface; the board spans {@code -thickness..0}. Rotation is about Z. */
    public record SolidView(String id, UUID ownerId, String label, String role, String shape, double xMm,
                            double yMm, double zMm, double sizeXMm, double sizeYMm, double sizeZMm,
                            double rotationDeg, String color) {
    }

    public record AssemblyView(UUID projectId, long revision, double boardWidthMm, double boardHeightMm,
                               double boardThicknessMm, double maxHeightMm, List<SolidView> solids,
                               List<Diagnostic> findings) {
    }
}
