# Cross-Platform EDA Workbench --- Project Plan

> **Document status:** Working baseline\
> **Version:** 0.1\
> **Date:** 2026-10-17\
> **Product direction:** A desktop electronic-design-automation (EDA)
> workbench inspired by Proteus, with schematic capture, component
> authoring, circuit simulation, PCB design, and 3D/mechanical
> validation.\
> **Primary engineering choices:** JavaFX desktop UI; Java application
> services; versioned JSON as the canonical project format; MCP support
> from the first release; selective Rust for proven performance or
> correctness needs; pluggable external engines.

------------------------------------------------------------------------

## 1. Executive summary

Build a cross-platform desktop EDA application that lets users design
electronic circuits from schematic through simulation, PCB layout, and
mechanical/3D inspection. The application should be useful
incrementally: each release must provide a coherent, testable capability
rather than requiring every subsystem to be complete before the product
is useful.

The application will use a **modular-monolith architecture** for core
domain logic, with clear interfaces around simulation, PCB, 3D geometry,
and other computationally expensive or third-party engines. The desktop
interface and primary orchestration layer will be Java/JavaFX. Rust may
be used selectively, but only when profiling, safety, or a concrete
integration need justifies it. Windows and Linux are first-class
platforms; the architecture should not prevent macOS support.

A versioned **JSON project model** is the canonical source of truth.
Rendered diagrams, spatial indexes, solver models, cached results, and
other derived data must be rebuildable and tied to the
revision/fingerprint of the source model. The user interface and AI
clients must use the same application-service command/query boundary.
**Model Context Protocol (MCP) support is a first-release requirement**,
not a later add-on.

The plan deliberately leaves the PCB engine undecided. A documented
evaluation must compare integration with an established engine, a custom
engine, and a hybrid approach before that choice is locked.

### Product principles

1.  **Correctness before convenience:** connectivity, units, pin
    mapping, design rules, and simulation assumptions must be explicit
    and testable.
2.  **Incremental value:** deliver a small end-to-end workflow early,
    then expand it.
3.  **Canonical model:** all interfaces operate on one authoritative
    domain model; views and engine representations are derived.
4.  **AI-friendly, not AI-dependent:** MCP makes capabilities
    discoverable and actionable, but the application remains fully
    usable without an AI client.
5.  **Cross-platform by design:** platform-specific code is isolated
    behind interfaces and verified in CI.
6.  **Interoperability with honest loss reporting:** imports and exports
    must report unsupported or approximated features.
7.  **Reproducibility:** simulations, validations, and generated outputs
    record their inputs, engine versions, and relevant assumptions.
8.  **No silent semantic changes:** library updates and migrations must
    not silently alter the meaning of existing projects.

------------------------------------------------------------------------

## 2. Goals, non-goals, and success measures

### 2.1 Goals

-   Provide a desktop application with five main workspaces:
    1.  Schematic editor
    2.  Component library and designer
    3.  Circuit simulator
    4.  PCB designer
    5.  3D visualization and mechanical integration
-   Support common electronic components, hierarchical designs, reusable
    blocks, named nets, power definitions, and design validation.
-   Support a hybrid simulation architecture through pluggable engine
    adapters.
-   Generate PCB designs from schematic connectivity and component
    footprint mappings.
-   Inspect board assemblies in 3D, including imported mechanical parts
    and clearance envelopes.
-   Use versioned JSON as the native project format and provide explicit
    import/export adapters.
-   Expose safe, structured project operations through MCP from the
    first release.
-   Make build, test, package, and project behavior consistent across
    supported operating systems.

### 2.2 Non-goals for the initial releases

-   Replacing every professional EDA tool or supporting every
    vendor-specific format.
-   Building a custom SPICE solver, autorouter, or full CAD geometry
    kernel before an evidence-based evaluation.
-   Claiming sign-off-grade thermal, electromagnetic, signal-integrity,
    or mechanical analysis without validated models and clearly defined
    limitations.
-   Automatically executing arbitrary code or external tools supplied by
    an AI client.
-   Guaranteeing lossless conversion for formats whose semantics cannot
    be represented in the native model.
-   Treating CNC isolation milling or GRBL control as part of the core
    EDA workflow. If required later, it should be a separate, explicitly
    scoped capability; Gerber is the normal PCB fabrication interchange
    format.

### 2.3 Success measures

Initial targets should be made measurable during the foundation phase.
Suggested measures:

-   A clean checkout builds and runs on every declared supported OS in
    CI.
-   A project can be saved, closed, reopened, and validated without
    semantic changes.
-   Connectivity and validation results are deterministic for the same
    project revision and engine versions.
-   Every mutation from JavaFX or MCP passes through the same validation
    and revision-control path.
-   MCP clients can discover capabilities and safely inspect a project
    without direct file manipulation.
-   At least one complete vertical workflow works: create/open project →
    place components → connect pins → validate → save/reopen.
-   Every external engine has a capability declaration,
    version/provenance reporting, and automated contract tests.
-   Import/export reports unsupported features and preserves all
    supported semantics in round-trip tests.

------------------------------------------------------------------------

## 3. Scope and workspaces

### 3.1 Schematic editor

The schematic editor supports:

-   Placing components from built-in and imported libraries.
-   Common passive components, diodes/LEDs, switches, relays,
    connectors, transistors, common ICs, microcontrollers, and
    development boards as the library grows.
-   Standard and custom symbols, pins, labels, wires, junctions, buses,
    and connection references.
-   Local, global, sheet, and hierarchical net-label scopes.
-   Hierarchical sheets, reusable blocks, and explicit ports.
-   Power definitions including voltage/current, source impedance,
    tolerance, and reference ground.
-   Component properties, ratings, tolerances, simulation parameters,
    package variants, and footprint assignments.
-   Diagnostics for unconnected pins, conflicting nets, duplicate
    references, invalid pin mappings, and unsupported features.

**Connectivity rule:** electrical identity is represented by stable net
and pin identifiers in the domain model. Wires, labels, and junction
graphics are editable representations of connectivity, not the sole
source of truth.

### 3.2 Component library and designer

The library system supports:

-   Search, filtering, indexing, bulk import, duplicate detection, and
    provenance.
-   Custom symbol and pin editing.
-   Footprints, pad numbering, pin-to-pad mapping, 3D models, and
    simulation models.
-   Package variants and explicit readiness/capability flags:
    schematic-ready, PCB-ready, simulation-ready, and 3D-ready.
-   Ratings, tolerances, manufacturer metadata, source, version, and
    licensing/provenance fields where available.
-   Immutable or versioned library definitions so an update cannot
    silently change an existing design.

A project should pin or snapshot the exact component-definition version
it uses, or record an explicit migration decision when the definition
changes.

### 3.3 Circuit simulator

Simulation is a hybrid system based on adapters to established engines
and, where justified, custom engines. Planned capabilities include:

-   DC operating point and transient analysis.
-   Common passive and semiconductor models.
-   Digital logic and later mixed-signal workflows.
-   Interactive inputs, time-indexed input tables, reusable source
    definitions, and simulation scenarios.
-   Voltage/current probes, waveforms, scope-like views, and
    logic-analyzer-like views.
-   Automated assertions and regression scenarios.
-   Optional firmware execution for selected
    microcontrollers/development boards in an advanced phase.
-   Electrical rating and power checks; thermal estimates only when
    model assumptions and limitations are visible.
-   Saved results with model, engine, configuration, and source-project
    provenance.

Do not conflate electrical ratings, approximate thermal estimates, and
validated physical simulation. Reports must distinguish them.

### 3.4 PCB designer

Planned capabilities:

-   Generate a PCB starting point from schematic components, package
    mappings, and net connectivity.
-   Board outline, cutouts, holes, layers, stackup, placement, manual
    routing, vias, copper zones, and planes.
-   Global design rules and net classes.
-   Trace width, clearance, via/drill dimensions, annular rings, edge
    clearance, and later differential-pair and length-matching rules.
-   DRC findings with severity, affected objects, measured/expected
    values, navigation, and actionable remediation.
-   Fabrication and assembly outputs: Gerber, Excellon, BOM CSV,
    pick-and-place, and assembly drawings.
-   Later support for autorouting and solder-paste/stencil generation.

**Engine decision gate:** compare established-engine integration, custom
implementation, and hybrid integration. Evaluate license and
redistribution terms, API stability, OS coverage, headless operation,
import/export fidelity, routing and DRC capabilities, performance,
testability, and maintainability. KiCad formats may be useful
interoperability targets, but format support must not be mistaken for a
stable embeddable engine.

### 3.5 3D visualization and mechanical integration

Planned capabilities:

-   Assemble a 3D board view from footprints and component models.
-   Import supported mechanical formats such as STEP.
-   Configure model origin, units, scale, orientation, and placement
    transforms.
-   Add non-electrical mechanical objects such as heatsinks, brackets,
    fuse holders, and enclosure parts.
-   Check component height, board/enclosure fit, connector access,
    mounting-hole alignment, and collisions.
-   Distinguish actual solid geometry from clearance envelopes.
-   Evaluate Open CASCADE Technology or other geometry-kernel options,
    including licensing, packaging, and cross-platform constraints.
-   Add mechanical CAD export in a later phase.

### 3.6 Interoperability

The native project is JSON. Explicit adapters should be provided for
selected external formats, prioritized by user value and implementation
cost:

-   Netlists and SPICE netlists.
-   Gerber and Excellon.
-   BOM and assembly CSV outputs.
-   Selected EDA formats, potentially including KiCad.
-   STEP and other selected mechanical formats.
-   Component-library import formats as supported by the selected
    ecosystem.

Every adapter must define supported features, unsupported features,
approximation behavior, units, identity mapping, and round-trip
guarantees. A conversion report must identify losses rather than
silently dropping information.

------------------------------------------------------------------------

## 4. Project guidelines

These guidelines are normative. New features, pull requests, generated
code, and AI-assisted changes should follow them.

### 4.1 Architecture and dependency rules

1.  **Keep the core domain independent of JavaFX.** UI controls and
    rendering types must not leak into domain entities or persistence
    schemas.
2.  **Use application services as the shared entry point.** JavaFX and
    MCP invoke the same commands and queries; neither writes project
    JSON directly.
3.  **Keep a modular monolith until evidence justifies separation.** Do
    not split modules into network services merely for architectural
    fashion. Separate processes are appropriate for isolation, external
    engines, crash containment, and expensive computations.
4.  **Use explicit ports/adapters for engines and file formats.**
    Third-party engine details must not spread through the domain model.
5.  **Use Rust selectively.** A Rust module requires a documented need
    such as measured performance, memory safety around a low-level
    subsystem, or an ecosystem dependency. Benchmark and integration
    costs must be considered.
6.  **No hidden cross-module writes.** State changes go through named
    commands and produce revisioned domain events/change sets.
7.  **Derived state is disposable.** Render caches, spatial indexes,
    solver models, and similar artifacts can be rebuilt from canonical
    state and must carry source revision/fingerprint metadata.

### 4.2 Domain model and persistence

1.  Use stable IDs for projects, sheets, components, pins, nets, rules,
    assets, and simulation scenarios. Never use display names or array
    positions as identity.
2.  Store explicit pin-to-net membership. Do not infer electrical
    connectivity from screen coordinates alone.
3.  Represent units and coordinate conventions explicitly. Avoid
    unlabelled numeric values where a unit or coordinate frame matters.
4.  Version every persisted schema and provide tested migrations for
    supported old versions.
5.  Serialize deterministically where practical to improve diffs,
    review, reproducibility, and test stability.
6.  Write atomically where the platform allows; retain recovery behavior
    for interrupted saves.
7.  Preserve unknown extension data when safe. Never silently
    reinterpret unknown fields.
8.  Keep large binary assets outside the main JSON document and
    reference them through a versioned asset manifest. A portable
    archive/container may package the JSON and assets, while JSON
    remains the canonical model.
9.  Library changes must be explicit and reviewable. Projects should
    retain the exact component definition version or a snapshot
    sufficient to reproduce the design.
10. Persist simulation scenarios and result provenance; do not imply
    results are reproducible if required model or engine versions are
    unavailable.

### 4.3 Cross-platform engineering

1.  Windows and Linux are first-class release targets. Keep macOS
    feasible unless a documented decision changes the product scope.
2.  Use Java/JavaFX portable APIs for core application behavior.
3.  Isolate OS-specific packaging, file associations, native libraries,
    and process behavior behind adapters.
4.  Do not assume path separators, case sensitivity, shell availability,
    executable names, temporary-directory locations, or filesystem
    atomicity are identical across OSes.
5.  Test native library loading, engine startup/cancellation, project
    save/recovery, MCP transport, and packaging on each supported OS.
6.  Prefer portable protocols/process boundaries for external engines
    when that materially reduces native binding risk.
7.  Any platform-specific limitation must be documented in the feature
    capability report and test matrix.

### 4.4 MCP and AI interaction

1.  MCP is a first-class interface from the first release.
2.  MCP tools must use the same application services, validation,
    authorization, and revision checks as the UI.
3.  Start with local stdio transport. Do not expose an unauthenticated
    network listener by default. Add Streamable HTTP only with an
    explicit security model.
4.  Separate read-only inspection from mutations and execution.
5.  Give tools strict JSON schemas, stable machine-readable IDs,
    structured errors, and explicit project revisions.
6.  For multi-step or consequential edits, support preview followed by
    an explicit apply/commit operation with an expected revision.
7.  Apply edits transactionally where possible: a batch should either
    succeed as a whole or report a clearly defined partial outcome;
    prefer all-or-nothing semantics.
8.  Require configurable approval for destructive or high-impact
    operations. Read-only tools may be available without repeated
    approval.
9.  Do not execute arbitrary code, shell commands, or imported project
    content just because an AI client requests it.
10. Treat tool arguments, imported files, component metadata, and model
    output as untrusted input.
11. Provide capability discovery, pagination for large results,
    cancellation for long-running tasks, and progress reporting where
    supported.
12. Audit mutation and execution requests with caller/tool, project
    revision, action, outcome, and relevant engine version, while
    avoiding secrets and unnecessary personal data.
13. Keep the product useful without an AI client. MCP must extend the
    application, not become a hidden dependency of core functionality.

### 4.5 Engineering quality and testing

1.  Every feature must include acceptance criteria; use Gherkin where
    behavior can be stated as scenarios.
2.  Tests should prefer observable behavior over private implementation
    details.
3.  Unit tests cover domain invariants and algorithms; integration tests
    cover persistence, engine adapters, and application services; UI
    tests cover critical user journeys; end-to-end tests cover
    representative workflows.
4.  Use contract tests for engine adapters, MCP schemas, and
    import/export adapters.
5.  Keep a small, deterministic fixture project for regression tests.
6.  For geometry, connectivity, simulation, and conversion, include edge
    cases and negative tests, not only happy paths.
7.  A defect fix should include a regression test when practical.
8.  Avoid flaky tests dependent on timing, external services, or
    nondeterministic solver output. Where exact numerical equality is
    inappropriate, define tolerances and convergence criteria.
9.  Keep dependency versions pinned or constrained reproducibly. Track
    licenses and redistribution obligations for all bundled engines and
    native libraries.
10. Build artifacts must be traceable to a source revision and build
    configuration.

### 4.6 Code and change management

-   Keep changes small enough to review and validate independently.
-   Each feature should have: problem statement, domain impact, design
    notes, acceptance criteria, tests, and documentation.
-   Avoid premature abstraction; introduce extension points at real
    subsystem boundaries (simulation, PCB, geometry, import/export,
    MCP), not for every class.
-   Do not allow generated code or AI-authored changes to bypass review,
    build, tests, security checks, or license checks.
-   Do not silently update persisted formats, component definitions, or
    external-engine behavior.
-   Document decisions with short Architecture Decision Records (ADRs),
    especially for language boundaries, JSON schema evolution, engine
    selection, and licensing.
-   Prefer meaningful diagnostics with stable codes and affected object
    IDs over generic error messages.
-   Never report a feature as supported merely because a file can be
    opened; specify which semantics are actually preserved.

------------------------------------------------------------------------

## 5. Architecture

### 5.1 Logical architecture

``` mermaid
flowchart TB
    Human[Human user] --> UI[JavaFX desktop UI]
    AI[AI client] --> MCP[MCP server]
    UI --> App[Java application services]
    MCP --> App
    App --> Cmd[Commands / Queries / Validation]
    Cmd --> Domain[Canonical domain model]
    Domain <--> Repo[Versioned JSON project repository]
    App --> Library[Component library service]
    App --> Schematic[Schematic and connectivity]
    App --> Validation[Design validation / diagnostics]
    App --> Sim[Simulation orchestration]
    App --> PCB[PCB engine adapter]
    App --> CAD[3D / geometry adapter]
    App --> IO[Import / export adapters]
    Sim --> Engines[External or custom simulation engines]
    PCB --> PCBEngine[Selected PCB engine]
    CAD --> Kernel[Geometry kernel]
    App -. selective FFI or process protocol .-> Rust[Rust subsystem, only when justified]
    Schematic --> Derived[Rebuildable derived state]
    Sim --> Derived
    PCB --> Derived
    CAD --> Derived
```

**Boundary rule:** the domain model and application services define
product semantics. Engine-specific objects are translated at adapter
boundaries. UI and MCP are clients of the same application layer.

### 5.2 Runtime and deployment

``` mermaid
flowchart LR
    subgraph Desktop["Desktop process"]
        FX[JavaFX UI]
        AS[Application services]
        DM[Domain model]
        JR[JSON repository]
        MCP[MCP stdio server]
        FX --> AS
        MCP --> AS
        AS --> DM
        DM <--> JR
    end
    subgraph Workers["Isolated workers / native engines"]
        SPICE[Simulation engine]
        PEngine[PCB engine]
        GKernel[3D geometry engine]
    end
    AS --> SPICE
    AS --> PEngine
    AS --> GKernel
```

Use in-process adapters when dependency stability and failure isolation
are acceptable. Use a worker process when a third-party engine has
native dependency conflicts, is crash-prone, requires a separate
runtime, or benefits from cancellation/resource limits. The protocol
should be versioned and machine-readable.

### 5.3 Module responsibilities

  ------------------------------------------------------------------------
  Module                   Owns                    Must not own
  ------------------------ ----------------------- -----------------------
  Desktop shell            Windows, navigation,    Electrical semantics or
                           workspace lifecycle,    persistence schema
                           user interaction        rules

  Application services     Use cases,              UI rendering and engine
                           command/query dispatch, internals
                           validation              
                           orchestration, revision 
                           checks                  

  Domain model             IDs, entities,          JavaFX or external
                           invariants,             engine classes
                           connectivity identity,  
                           design intent           

  Project repository       JSON schema,            UI-specific state
                           migrations, assets,     
                           atomic save/recovery    

  Component library        Definitions, versions,  Silent mutation of
                           metadata, capability    project instances
                           flags                   

  Schematic/connectivity   Sheets, symbols, pins,  PCB routing algorithms
                           nets, hierarchy,        
                           connectivity derivation 

  Validation               Rules, diagnostics,     Hidden mutation of the
                           severity, affected IDs, design
                           remediation hints       

  Simulation orchestration Scenarios, engine       Solver-specific
                           selection, job          implementation details
                           lifecycle, provenance,  
                           results                 

  PCB adapter              Mapping between native  Owning the
                           model and selected PCB  authoritative schematic
                           engine                  model

  3D adapter               Model placement,        Treating visual meshes
                           coordinate transforms,  as electrical
                           geometry checks         connectivity

  Interoperability         Format-specific         Unreported semantic
                           import/export and loss  loss
                           reports                 

  MCP server               Tool/resource/prompt    Direct persistence
                           exposure, schemas,      writes or duplicate
                           permissions, audit      domain logic
  ------------------------------------------------------------------------

### 5.4 Commands, queries, and events

Commands express intent and may change the project; queries are
read-only. Example names:

**Queries** - `project.get_summary` - `project.get_structure` -
`library.search_components` - `schematic.get_component` -
`schematic.get_net` - `design.get_violations` -
`design.get_capabilities` - `simulation.get_results`

**Commands / jobs** - `schematic.place_component` -
`schematic.connect_pins` - `schematic.set_property` -
`design.validate` - `simulation.run` - `project.preview_changes` -
`project.apply_changes`

Every command should specify required permissions, expected revision,
validation behavior, and result shape. Long-running jobs should return a
job identifier and support status/progress/cancellation where practical.

Possible domain events include `ComponentPlaced`,
`ComponentPropertyChanged`, `PinMappingChanged`,
`NetConnectivityChanged`, `PowerDefinitionChanged`,
`BoardGeometryChanged`, `DesignRulesChanged`, and
`LibraryDefinitionUpdated`. Events identify affected stable IDs and the
resulting project revision. Events are notifications/change records, not
a substitute for authoritative persisted state.

### 5.5 MCP surface

**Initial read-only tools** - `project.get_summary` -
`project.get_structure` - `library.search_components` -
`schematic.get_component` - `schematic.get_net` -
`design.get_violations` - `design.get_capabilities`

**Controlled mutation and execution tools** -
`schematic.place_component` - `schematic.connect_pins` -
`schematic.set_property` - `design.validate` - `simulation.run` -
`project.preview_changes` - `project.apply_changes`

**Candidate resources** - `eda://project/summary` -
`eda://project/structure` - `eda://project/components` -
`eda://project/nets` - `eda://project/diagnostics` -
`eda://project/design-rules` - `eda://project/capabilities`

**Candidate prompts** - Review this schematic for connectivity errors. -
Explain the purpose and connectivity of this hierarchical module. - Find
component rating violations. - Suggest PCB placement/routing
improvements. - Produce a design-review checklist. - Generate a
simulation test plan.

These are planned API names, not a claim that the final MCP schema has
already been standardized. Final tool schemas should be reviewed and
versioned before release.

------------------------------------------------------------------------

## 6. Native JSON project format

### 6.1 Top-level model

The initial schema should cover these areas, even if some are empty or
feature-gated in early releases:

``` json
{
  "schemaVersion": "0.1",
  "projectId": "stable-uuid",
  "metadata": {
    "name": "Example circuit",
    "createdAt": "2026-10-17T00:00:00Z",
    "modifiedAt": "2026-10-17T00:00:00Z"
  },
  "libraryReferences": [],
  "componentDefinitions": [],
  "sheets": [],
  "nets": [],
  "powerDefinitions": [],
  "designRules": {},
  "pcb": {},
  "assembly3d": {},
  "simulationScenarios": [],
  "assetManifest": [],
  "extensions": {}
}
```

This is an illustrative shape, not the final schema. Detailed schemas
should be split into versioned documents for projects, components,
design rules, simulation scenarios, and MCP requests/responses.

### 6.2 Persistence requirements

-   Stable IDs for all addressable entities.
-   Explicit pin-to-net membership and explicit pin mapping to pads.
-   Explicit units, coordinate frames, orientation conventions, and
    transform semantics.
-   Schema version and deterministic migration path.
-   Atomic save and recoverable backup/autosave strategy.
-   Validation on load with clear diagnostics for invalid or unsupported
    content.
-   Preservation of unknown extension fields where safe.
-   Assets stored externally and referenced by stable manifest entries;
    optional archive packaging may bundle them.
-   Derived artifacts keyed by source revision/fingerprint and safe to
    discard/rebuild.
-   Project-level feature/capability declarations so older clients can
    identify unsupported sections.
-   Migration tests with fixtures from every supported schema version.

### 6.3 Connectivity model

The model should make the following relationship explicit:

``` mermaid
erDiagram
    PROJECT ||--o{ SHEET : contains
    SHEET ||--o{ COMPONENT_INSTANCE : places
    COMPONENT_DEFINITION ||--o{ PIN_DEFINITION : defines
    COMPONENT_INSTANCE }o--|| COMPONENT_DEFINITION : references
    COMPONENT_INSTANCE ||--o{ PIN_INSTANCE : exposes
    PIN_INSTANCE }o--o| NET : connects_to
    PROJECT ||--o{ NET : defines
    PROJECT ||--o{ DESIGN_RULE : configures
```

A net may connect pins across hierarchy only through explicit
hierarchical connectivity rules. Names are labels and may be duplicated
in scoped contexts; they are not stable identity.

------------------------------------------------------------------------

## 7. Requirements and Gherkin acceptance criteria

The scenarios below are starting acceptance criteria. They should be
converted into executable tests and expanded with edge cases, error
handling, and platform-specific coverage. IDs are stable references for
backlog items.

### 7.1 Foundation and project lifecycle

#### `PRJ-001` --- Create, save, and reopen a project

``` gherkin
Feature: Project lifecycle
  Scenario: Save and reopen a valid project
    Given the user has an open project with a valid domain model
    When the user saves the project and closes it
    And the user reopens the saved project
    Then the project entities and stable identifiers are preserved
    And the connectivity graph is equivalent to the saved graph
    And no migration or validation warning is hidden from the user
```

#### `PRJ-002` --- Reject unsupported schema versions safely

``` gherkin
Feature: Project schema compatibility
  Scenario: Open a project from a newer unsupported schema
    Given a project file declares a schema version newer than the application supports
    When the user attempts to open the project
    Then the application does not overwrite or downgrade the file
    And it explains that the schema version is unsupported
    And it offers a safe read-only inspection path if one is implemented
```

#### `PRJ-003` --- Recover from interrupted save

``` gherkin
Feature: Project recovery
  Scenario: Save interruption does not corrupt the last valid project
    Given a valid project file exists
    When a save operation is interrupted before its atomic replacement completes
    Then the previous valid project remains recoverable
    And the application reports whether recovery data is available
```

#### `ARC-001` --- Keep UI and domain separated

``` gherkin
Feature: Architecture boundaries
  Scenario: Domain tests run without JavaFX initialization
    Given the domain and application-service test suites are executed
    When they run in a headless CI environment
    Then they do not require a JavaFX toolkit
    And they do not instantiate UI controls to validate domain invariants
```

### 7.2 Cross-platform build and runtime

#### `XPL-001` --- Build on every supported OS

``` gherkin
Feature: Cross-platform build
  Scenario Outline: Build and test on a supported operating system
    Given a clean checkout of the repository
    When CI builds and tests the application on "<os>"
    Then compilation and required automated tests pass
    And the build records the source revision and dependency versions

    Examples:
      | os      |
      | Windows |
      | Linux   |
```

Add macOS to the matrix when it becomes a declared supported build
target.

#### `XPL-002` --- Portable project path handling

``` gherkin
Feature: Portable file handling
  Scenario: Open and save a project from a path containing spaces and Unicode
    Given a project is stored in a directory whose path contains spaces and Unicode characters
    When the user opens, edits, and saves the project
    Then the application reads and writes the intended file
    And no shell-specific path interpretation is required
```

#### `XPL-003` --- Engine capability is explicit

``` gherkin
Feature: Platform engine availability
  Scenario: An engine is unavailable on the current platform
    Given a project requests a capability whose engine is unavailable on the current OS
    When the user attempts to use that capability
    Then the application reports the missing capability and reason
    And unrelated project editing remains available
    And the project is not silently modified
```

### 7.3 Schematic and connectivity

#### `SCH-001` --- Place a component

``` gherkin
Feature: Schematic editing
  Scenario: Place a library component on a sheet
    Given a project contains a sheet and a library component is available
    When the user places that component on the sheet
    Then a component instance with a stable unique ID is created
    And each pin instance refers to the selected component definition
    And the project revision advances
```

#### `SCH-002` --- Connect pins explicitly

``` gherkin
Feature: Electrical connectivity
  Scenario: Connect two pins to the same net
    Given two compatible pin instances exist in a project
    When the user connects the pins
    Then both pins resolve to the same net identity
    And connectivity queries return both pins as members of that net
    And the saved and reloaded project preserves the connection
```

#### `SCH-003` --- Distinguish visual crossing from a junction

``` gherkin
Feature: Schematic wire semantics
  Scenario: Crossing wires without a junction remain separate
    Given two wires cross visually at a point without a junction
    When the connectivity graph is derived
    Then the wires remain electrically separate
    And adding an explicit junction changes connectivity only according to the documented rules
```

#### `SCH-004` --- Validate unconnected pins

``` gherkin
Feature: Schematic diagnostics
  Scenario: Report a required unconnected pin
    Given a component has a pin marked as required for connectivity
    And the pin is not connected to a net
    When the user runs design validation
    Then a diagnostic identifies the component and pin by stable ID
    And the diagnostic includes a severity and actionable explanation
```

#### `SCH-005` --- Preserve hierarchical connectivity

``` gherkin
Feature: Hierarchical schematic
  Scenario: Connect a child-sheet port to its parent net
    Given a child sheet exposes a named hierarchical port
    And the parent sheet contains the matching port connection
    When the connectivity graph is derived
    Then the connection follows the configured hierarchy rules
    And local labels with the same text do not connect unless their scope rules say they should
```

### 7.4 Component library

#### `LIB-001` --- Search the component library

``` gherkin
Feature: Component discovery
  Scenario: Search by name and capability
    Given the library contains components with different capabilities
    When the user searches for a component and filters for PCB-ready items
    Then only matching components with the required capability are returned
    And each result includes its stable ID and definition version
```

#### `LIB-002` --- Library update does not silently alter projects

``` gherkin
Feature: Component definition versioning
  Scenario: A referenced library definition receives an update
    Given a project references a specific component definition version
    When a newer library definition becomes available
    Then the existing project remains bound to its recorded definition or snapshot
    And the application presents an explicit update or migration action
    And the update preview identifies potentially changed pins, mappings, or properties
```

#### `LIB-003` --- Validate pin-to-pad mapping

``` gherkin
Feature: Package mapping
  Scenario: Reject an incomplete required pad mapping
    Given a component has a footprint with required pads
    When a user assigns the footprint without mapping a required electrical pin
    Then the mapping is marked invalid
    And PCB generation is blocked or clearly downgraded according to the documented policy
    And the diagnostic identifies the missing pin and pad mapping
```

### 7.5 Validation and diagnostics

#### `VAL-001` --- Stable actionable diagnostics

``` gherkin
Feature: Design validation
  Scenario: Return structured validation results
    Given a project contains one or more design violations
    When validation runs
    Then each finding has a stable diagnostic code, severity, message, and affected IDs
    And numeric findings include units and measured/expected values when applicable
    And the result identifies the project revision that was validated
```

#### `VAL-002` --- Validation does not mutate the design

``` gherkin
Feature: Validation purity
  Scenario: Running validation leaves design state unchanged
    Given a project has revision R
    When design validation runs
    Then the project remains at revision R
    And diagnostics are returned separately from project mutations
```

### 7.6 MCP and AI access

#### `MCP-001` --- Read-only MCP project inspection

``` gherkin
Feature: MCP project inspection
  Scenario: MCP client reads project summary
    Given an MCP client is connected to the local application
    And a project is open
    When the client calls "project.get_summary"
    Then it receives a schema-valid summary with stable IDs and project revision
    And the project is not modified
```

#### `MCP-002` --- MCP mutation uses the application service

``` gherkin
Feature: MCP schematic mutation
  Scenario: Place a component through MCP
    Given a project is open at revision R
    And the caller is authorized to edit the schematic
    When the caller requests a valid component placement with expected revision R
    Then the request passes through the same validation path as a UI edit
    And the component is created with a stable ID
    And the response returns the new revision and created entity ID
```

#### `MCP-003` --- Reject stale revision

``` gherkin
Feature: MCP optimistic concurrency
  Scenario: Prevent applying a stale edit
    Given an MCP client reads project revision R
    And a human edit advances the project to revision R+1
    When the client submits a mutation expecting revision R
    Then the application rejects the mutation as a revision conflict
    And the current revision is returned
    And no partial mutation is applied
```

#### `MCP-004` --- Preview before applying a batch

``` gherkin
Feature: MCP change preview
  Scenario: Preview and apply a valid change set
    Given a client proposes a batch of schematic changes
    When it requests a preview
    Then the application returns the proposed changes and validation findings
    And the project remains unchanged
    When the authorized client applies the preview against the same expected revision
    Then the batch is applied transactionally
    And the response identifies the resulting revision and changed entities
```

#### `MCP-005` --- MCP is secure by default

``` gherkin
Feature: MCP transport security
  Scenario: Start the default local MCP server
    Given the application is launched with default settings
    When the MCP server starts
    Then it uses the configured local stdio transport
    And it does not expose an unauthenticated network listener
    And mutation and execution permissions follow the configured policy
```

#### `MCP-006` --- Unsupported capability is reported honestly

``` gherkin
Feature: MCP capability discovery
  Scenario: AI client requests an unavailable engine capability
    Given the current installation does not support a requested engine capability
    When an MCP client queries capabilities or requests that operation
    Then the response reports the capability as unavailable
    And it includes a machine-readable reason where possible
    And no unsupported operation is reported as successful
```

### 7.7 Simulation

#### `SIM-001` --- Run a reproducible simulation scenario

``` gherkin
Feature: Simulation scenarios
  Scenario: Run and save a supported transient simulation
    Given a valid circuit and a supported simulation scenario exist
    When the user runs the scenario
    Then the selected engine receives a derived model for the recorded project revision
    And results include units, engine identity/version, scenario settings, and provenance
    And the original circuit model is not modified by solver execution
```

#### `SIM-002` --- Cancel a long-running simulation

``` gherkin
Feature: Simulation job lifecycle
  Scenario: Cancel a running simulation
    Given a simulation job is running and supports cancellation
    When the user requests cancellation
    Then the orchestration layer requests cancellation from the engine
    And the job reaches a documented cancelled or cancellation-failed state
    And the UI reports the final state without pretending incomplete results are complete
```

#### `SIM-003` --- Report model limitations

``` gherkin
Feature: Simulation result interpretation
  Scenario: Results depend on an approximate or incomplete model
    Given a component model or analysis method has documented limitations
    When the simulation report is presented
    Then the report identifies the relevant limitation and assumptions
    And estimated thermal results are not presented as validated physical measurements
```

#### `SIM-004` --- Check component ratings

``` gherkin
Feature: Electrical rating checks
  Scenario: Simulation exceeds a component rating
    Given a component has a defined voltage, current, or power rating
    And the simulation produces a value beyond that rating
    When rating checks are evaluated
    Then a diagnostic identifies the component, metric, limit, observed value, and units
    And the diagnostic is linked to the simulation scenario and result provenance
```

### 7.8 PCB design

#### `PCB-001` --- Generate a PCB starting point

``` gherkin
Feature: Schematic-to-PCB transfer
  Scenario: Create a board from PCB-ready schematic components
    Given the schematic is valid for PCB transfer
    And every transferred component has a valid footprint and pin-to-pad mapping
    When the user generates the PCB starting point
    Then the board contains the transferred components and net connectivity
    And unmapped or unsupported objects are reported explicitly
    And the source schematic remains unchanged
```

#### `PCB-002` --- Run design-rule checks

``` gherkin
Feature: PCB design-rule checking
  Scenario: Report a trace clearance violation
    Given two copper objects violate the configured clearance rule
    When PCB DRC runs
    Then a finding identifies the affected objects and rule
    And it includes the measured clearance, required clearance, and units
    And the finding can navigate to the relevant board location
```

#### `PCB-003` --- Export fabrication files

``` gherkin
Feature: PCB fabrication export
  Scenario: Export a valid board
    Given a board passes the required fabrication checks
    When the user exports Gerber and Excellon files
    Then the export includes the configured layers and drill data
    And a manifest records the exported files and relevant settings
    And any omitted or unsupported output is reported
```

#### `PCB-004` --- Engine adapter contract

``` gherkin
Feature: PCB engine adapter
  Scenario: Adapter round-trips supported board data
    Given a board fixture uses only features supported by the selected engine adapter
    When the board is exported to the engine and imported back
    Then supported connectivity, geometry, units, and object identities remain equivalent
    And any known normalization is documented and asserted in the test
```

### 7.9 3D and mechanical integration

#### `3D-001` --- Assemble a board in 3D

``` gherkin
Feature: 3D board assembly
  Scenario: Render components with available 3D models
    Given a PCB has footprints and some components have compatible 3D models
    When the user opens the 3D workspace
    Then the board and available component models are positioned using documented transforms
    And missing models are reported without preventing the rest of the assembly from rendering
```

#### `3D-002` --- Detect mechanical collision

``` gherkin
Feature: Mechanical clearance
  Scenario: Detect a component intersecting an enclosure
    Given an enclosure solid and component geometry or clearance envelopes are defined
    When mechanical validation runs
    Then a collision or clearance finding identifies both objects
    And it states whether the finding uses solid geometry or a clearance envelope
    And the result includes units and the configured tolerance
```

### 7.10 Import/export and interoperability

#### `IO-001` --- Report conversion losses

``` gherkin
Feature: Import and export diagnostics
  Scenario: Export a project with unsupported source features
    Given a project contains features not representable in the target format
    When the user exports the project
    Then the application reports each unsupported or approximated feature
    And the report identifies affected entities where possible
    And the user is not told that the conversion is lossless
```

#### `IO-002` --- Preserve supported semantics in round-trip conversion

``` gherkin
Feature: Format round-trip
  Scenario: Round-trip a supported subset
    Given a fixture project uses only features supported by the selected adapter
    When the project is exported and then imported
    Then supported connectivity, stable references, units, and component mappings are equivalent
    And expected format normalization is covered by explicit assertions
```

### 7.11 Component and asset safety

#### `SEC-001` --- Treat imported content as untrusted

``` gherkin
Feature: Safe import
  Scenario: Import a file containing unexpected content
    Given an import file contains malformed fields or unsupported embedded content
    When the user imports the file
    Then the importer validates the input against size and schema limits
    And it rejects or quarantines unsafe content with a clear diagnostic
    And it does not execute embedded code or commands
```

------------------------------------------------------------------------

## 8. Delivery roadmap

Delivery is organized around verified vertical slices. The release names
are planning gates, not calendar commitments; estimates should follow
the engine evaluations and first technical spikes.

``` mermaid
flowchart TD
    R0["R0 — Foundation<br/>JavaFX + JSON + schematic slice + read-only MCP"]
    R1["R1 — Simulation<br/>Engine adapter + scenarios + waveforms"]
    R2["R2 — PCB<br/>Engine decision + transfer + placement/routing + DRC"]
    R3["R3 — 3D<br/>Board assembly + mechanical imports + clearance"]
    R4["R4 — Advanced<br/>Firmware, autorouting, richer analyses, more formats"]
    R0 --> R1 --> R2 --> R3 --> R4
    Gate["Engine, license, and cross-platform gates"] -. informs .-> R1
    Gate -. informs .-> R2
    Gate -. informs .-> R3
```

### R0 --- Foundation and first end-to-end slice

**Objective:** establish a runnable cross-platform desktop application
with canonical project persistence, a useful schematic slice, and
read-only MCP.

Deliverables: - Repository layout, build pipeline, dependency/license
inventory, ADR template. - JavaFX shell and workspace navigation. -
Domain model and versioned JSON schema. - Create/open/save/reopen
project flow with validation and recovery strategy. - Component
definition/instance model, stable IDs, pins, nets, and explicit
connectivity. - Basic schematic rendering and component
placement/connection. - Structured diagnostics and a deterministic
fixture project. - Application-service command/query boundary. - MCP
stdio server with project summary, structure, component search, net
inspection, diagnostics, and capability discovery. - Windows and Linux
CI; packaging spike for macOS feasibility. - Architecture and security
documentation.

**Exit criteria:** - The first vertical workflow passes on Windows and
Linux: create/open → place components → connect pins → validate →
save/reopen. - Connectivity is preserved across save/reopen. - MCP
read-only queries return schema-valid results and cannot mutate the
project. - A documented ADR explains how optional Rust integration would
work and why it is or is not used initially.

### R1 --- Circuit simulation

**Objective:** run useful simulations through a stable engine adapter
without coupling the domain model to a solver.

Deliverables: - Simulation-engine evaluation and selected first
adapter. - Model translation, capability reporting, scenario
definitions, job lifecycle. - DC operating point and transient analysis
for the supported model subset. - Waveform viewer and voltage/current
probes. - Result provenance and reproducible fixture scenarios. - Rating
checks and clearly labelled model limitations. - MCP simulation tools
with authorization, job status, and cancellation where supported. -
Regression tests comparing outputs within declared numerical tolerances.

**Exit criteria:** - A documented set of circuits runs repeatably on
supported OSes. - Engine absence/failure is reported without corrupting
the project. - Simulation results are traceable to project revision,
engine version, and scenario settings.

### R2 --- PCB design

**Objective:** generate and edit a PCB using a deliberately selected
engine strategy.

Deliverables: - Weighted PCB engine benchmark and decision record. -
License/redistribution review and Windows/Linux/macOS feasibility
evidence. - Schematic-to-PCB transfer for PCB-ready components. - Board
outline, component placement, layers/stackup, manual routing, and basic
copper zones. - Initial DRC rules and structured findings. -
Gerber/Excellon, BOM, and pick-and-place outputs. - Import/export
feature matrix and adapter contract suite. - MCP read-only PCB
inspection and controlled edits after the UI workflow stabilizes.

**Exit criteria:** - A reference board can be transferred, edited,
checked, saved/reopened, and exported. - The selected engine's supported
feature subset and limitations are documented. - Fabrication outputs
pass format-level validation and are checked against known fixtures.

### R3 --- 3D and mechanical integration

**Objective:** inspect board assemblies and catch basic fit/clearance
problems.

Deliverables: - Geometry-kernel and model-format evaluation. -
Footprint-to-3D model mapping and transform conventions. - STEP import
where supported. - Mechanical object placement and enclosure/board fit
checks. - Collision and clearance-envelope diagnostics. - Cross-platform
packaging and performance tests.

**Exit criteria:** - A reference assembly renders with documented
coordinate conventions. - Missing models and unsupported geometry
produce explicit diagnostics. - Collision checks identify the involved
objects and distinguish solid geometry from envelope checks.

### R4 --- Advanced engineering workflows

Potential deliverables, selected based on user demand and technical
validation: - Firmware execution for selected microcontrollers and
development boards. - Mixed-signal simulation improvements. -
Autorouting, differential pairs, and length matching. - More
import/export formats and library ingestion workflows. -
Solder-paste/stencil generation. - Advanced thermal analysis, only with
validated models and clearly defined accuracy. - Mechanical CAD
export. - Additional MCP prompts/tools and batch workflows.

**Exit criteria:** each advanced capability has its own capability
declaration, validation strategy, platform matrix, documented
limitations, and acceptance suite.

------------------------------------------------------------------------

## 9. Backlog and work packages

These IDs are suitable for conversion into issues. Break each into
smaller tasks after the repository and build system exist.

### Architecture and platform

-   [ ] `ARC-01` --- Create JavaFX desktop shell and portable build
    pipeline.
-   [ ] `ARC-02` --- Define application-service command/query
    boundaries.
-   [ ] `ARC-03` --- Define canonical JSON domain model, schemas, stable
    IDs, and migrations.
-   [ ] `ARC-04` --- Prototype and document the Java/Rust integration
    policy; do not introduce Rust without a concrete need.
-   [ ] `ARC-05` --- Establish Windows/Linux CI and dependency/license
    policy; keep macOS feasibility visible.
-   [ ] `ARC-06` --- Add Architecture Decision Records for persistence,
    engine boundaries, and native integration.
-   [ ] `ARC-07` --- Add structured diagnostics, project revisions, and
    deterministic fixture projects.
-   [ ] `ARC-08` --- Implement atomic save, backup/recovery, and
    migration tests.

### MCP

-   [ ] `MCP-01` --- Implement standards-compliant MCP server and local
    stdio transport.
-   [ ] `MCP-02` --- Implement read-only
    project/component/net/diagnostic tools.
-   [ ] `MCP-03` --- Define schemas, structured errors, capability
    discovery, and revision semantics.
-   [ ] `MCP-04` --- Implement tool visibility, authorization, and audit
    policy.
-   [ ] `MCP-05` --- Test with at least one independent MCP client on
    each supported OS.
-   [ ] `MCP-06` --- Expose a validated mutation tool through the shared
    application services.
-   [ ] `MCP-07` --- Implement preview/commit for revision-checked
    change batches.
-   [ ] `MCP-08` --- Add pagination, cancellation, and progress
    reporting where applicable.

### Schematic and library

-   [ ] `SCH-01` --- Implement sheets, component instances, pins, and
    basic rendering.
-   [ ] `SCH-02` --- Implement stable net identities and explicit
    pin-to-net membership.
-   [ ] `SCH-03` --- Implement wires, junctions, labels, and scoped
    connectivity.
-   [ ] `SCH-04` --- Implement hierarchy and sheet ports.
-   [ ] `SCH-05` --- Implement required-pin and duplicate-reference
    diagnostics.
-   [ ] `LIB-01` --- Implement component search, filtering, and
    capability flags.
-   [ ] `LIB-02` --- Implement versioned definitions and project-pinned
    references/snapshots.
-   [ ] `LIB-03` --- Implement symbol/pin editor and footprint/pad
    mapping validation.
-   [ ] `LIB-04` --- Define asset manifest and supported
    component-library importers.

### Simulation

-   [ ] `SIM-01` --- Benchmark candidate simulation engines and document
    license/platform support.
-   [ ] `SIM-02` --- Define simulation adapter contract and capability
    schema.
-   [ ] `SIM-03` --- Implement DC and transient scenarios for the
    initial supported subset.
-   [ ] `SIM-04` --- Implement waveform/probe data model and viewer.
-   [ ] `SIM-05` --- Record simulation provenance and numerical
    tolerances.
-   [ ] `SIM-06` --- Add component rating checks and explicit model
    limitations.
-   [ ] `SIM-07` --- Add MCP run/status/cancel tools with appropriate
    permissions.

### PCB

-   [ ] `PCB-01` --- Define a weighted engine evaluation and
    representative benchmark fixtures.
-   [ ] `PCB-02` --- Complete engine license, API, headless, and
    cross-platform assessment.
-   [ ] `PCB-03` --- Record engine selection decision (established,
    custom, or hybrid).
-   [ ] `PCB-04` --- Implement schematic-to-PCB transfer and mapping
    diagnostics.
-   [ ] `PCB-05` --- Implement board outline, placement, layers,
    routing, and basic zones.
-   [ ] `PCB-06` --- Implement initial DRC and navigable diagnostics.
-   [ ] `PCB-07` --- Export Gerber, Excellon, BOM, pick-and-place, and
    assembly outputs.
-   [ ] `PCB-08` --- Add adapter contract, round-trip, and fixture-based
    export tests.

### 3D and interoperability

-   [ ] `3D-01` --- Define units, axes, handedness, and transform
    conventions.
-   [ ] `3D-02` --- Evaluate geometry kernels and distribution/licensing
    constraints.
-   [ ] `3D-03` --- Assemble PCB and component 3D models.
-   [ ] `3D-04` --- Import supported mechanical models and add
    mechanical objects.
-   [ ] `3D-05` --- Implement collision and clearance-envelope checks.
-   [ ] `IO-01` --- Define import/export feature matrices and
    loss-report format.
-   [ ] `IO-02` --- Implement SPICE netlist and BOM export.
-   [ ] `IO-03` --- Implement selected EDA and mechanical-format
    adapters.
-   [ ] `IO-04` --- Add round-trip fixtures and conversion-loss
    regression tests.

------------------------------------------------------------------------

## 10. Engine and technology decision gates

Do not make expensive engine commitments from a feature checklist alone.
Build representative spikes and compare real behavior.

### 10.1 PCB engine evaluation

Suggested weighted criteria (score each candidate 1--5 and record
evidence):

  Criterion                                        Suggested weight
  ---------------------------------------------- ------------------
  Cross-platform support and packaging                          15%
  License and redistribution compatibility                      15%
  API stability and embeddability                               15%
  Connectivity/geometry import-export fidelity                  15%
  DRC and routing capability                                    15%
  Headless automation and testability                           10%
  Performance on representative boards                           5%
  Long-term maintenance and community                            5%
  Fit with native JSON domain model                              5%

Test at least: - A simple two-layer board. - A board with multiple power
nets and zones. - A board with hierarchy-derived net names and several
package variants. - Invalid geometry and DRC violations. - Save/reopen
and supported-subset round-trip. - Windows/Linux packaging and headless
execution.

A high score is not enough if the license, distribution model, or
required OS support is unacceptable.

### 10.2 Simulation engine evaluation

Evaluate: - Model coverage and numerical robustness. - DC/transient
support and convergence diagnostics. - Digital/mixed-signal
extensibility. - License and redistribution. - Cross-platform binaries
or buildability. - Automation, cancellation, result extraction, and
testability. - Mapping fidelity from the canonical component model. -
Provenance and repeatability.

### 10.3 Rust integration decision

Use Rust only if a benchmark or safety requirement justifies it.
Evaluate: - Java Foreign Function and Memory API / C ABI options. - A
separate process with a versioned protocol. - Build and package
complexity on all supported OSes. - Crash isolation, memory ownership,
cancellation, and error mapping. - Whether the code is truly
performance-sensitive or can remain in Java.

Do not add JNI/FFI merely because Rust is available. Keep the boundary
narrow and stable.

### 10.4 3D geometry decision

Evaluate kernel license, file-format support, Boolean/collision
operations, coordinate transforms, native dependency size, packaging,
and automated testability. The 3D renderer and geometry checker may be
separate components if that makes the system more reliable.

------------------------------------------------------------------------

## 11. Testing and quality strategy

### 11.1 Test layers

  -----------------------------------------------------------------------------
  Layer                   Main purpose            Examples
  ----------------------- ----------------------- -----------------------------
  Domain unit tests       Enforce invariants      stable IDs, pin mapping, net
                                                  membership, units

  Application-service     Verify use cases        place/connect/validate/save
  tests                                           

  Persistence tests       Verify compatibility    migration, malformed JSON,
                          and recovery            atomic save

  Adapter contract tests  Contain third-party     simulation, PCB, geometry,
                          differences             import/export

  MCP contract tests      Keep AI API stable and  schema validation,
                          safe                    authorization, stale
                                                  revisions

  UI tests                Protect critical        create project,
                          workflows               place/connect, navigate
                                                  diagnostics

  End-to-end tests        Verify vertical slices  schematic → simulation;
                                                  schematic → PCB → fabrication
                                                  export

  Cross-platform tests    Detect OS-specific      paths, native loading,
                          regressions             process lifecycle, packaging

  Performance tests       Guard known expensive   large schematic loading, net
                          operations              derivation, DRC

  Security tests          Prevent unsafe          malformed files, path
                          imports/actions         traversal, untrusted tool
                                                  arguments
  -----------------------------------------------------------------------------

### 11.2 Determinism and numerical behavior

-   Pin engine versions in repeatability fixtures.
-   Record model version, solver settings, time step, tolerances, and
    input waveform.
-   Use exact comparisons for identity/connectivity and declared
    tolerances for numerical outputs.
-   Separate expected nondeterminism from regressions; do not widen
    tolerances without explaining why.
-   Validate unit conversion and coordinate transforms with known
    reference cases.
-   Keep generated files and caches out of canonical project semantics
    unless deliberately versioned.

### 11.3 Definition of Done

A feature is Done only when all applicable items are complete:

-   [ ] Requirements and scope are documented.
-   [ ] Acceptance criteria exist, preferably in Gherkin.
-   [ ] Domain invariants and error cases are implemented.
-   [ ] Unit/integration/contract tests pass.
-   [ ] Supported OS behavior is tested or a documented limitation
    exists.
-   [ ] Persistence changes include schema versioning/migration tests.
-   [ ] MCP exposure, permissions, and audit implications are reviewed
    if applicable.
-   [ ] Diagnostics identify affected stable IDs and provide actionable
    context.
-   [ ] Documentation and capability declarations are updated.
-   [ ] Dependencies and license implications are reviewed.
-   [ ] Build artifact is traceable to source revision.
-   [ ] No known unsupported behavior is presented as successful or
    lossless.

------------------------------------------------------------------------

## 12. Risks and mitigations

  -----------------------------------------------------------------------
  Risk                    Impact                  Mitigation
  ----------------------- ----------------------- -----------------------
  Java/Rust boundary      Build and packaging     Keep Rust optional;
  complexity              fragility               narrow ABI/protocol;
                                                  benchmark before
                                                  adoption

  PCB engine is difficult Schedule and            Run early spike; score
  to embed                architecture disruption real candidates;
                                                  preserve an adapter
                                                  boundary

  Native libraries differ Platform-specific       CI matrix, isolated
  across OSes             failures                adapters/workers,
                                                  packaging tests

  AI makes incorrect or   Project corruption or   Shared validation,
  destructive edits       user distrust           preview/commit,
                                                  expected revisions,
                                                  transactional edits,
                                                  audit

  JSON schema evolves     Lost data or unreadable Versioned schemas,
  incompatibly            projects                migrations, fixtures,
                                                  atomic writes,
                                                  unknown-field policy

  Connectivity semantics  Incorrect               Stable nets and
  become tied to graphics simulation/PCB transfer explicit pin
                                                  membership; dedicated
                                                  connectivity tests

  Imported library data   Invalid                 Provenance, capability
  is incomplete or        footprints/models or    flags, validation,
  misleading              unsafe output           explicit missing-data
                                                  diagnostics

  Simulation results are  Unsafe engineering      Show assumptions,
  over-trusted            conclusions             limitations,
                                                  tolerances, engine
                                                  provenance, and rating
                                                  warnings

  3D visual model differs False clearance         Track model
  from physical part      confidence              source/accuracy;
                                                  distinguish solid
                                                  geometry from envelopes

  Scope expands before a  Long delay before user  Gate releases on
  vertical slice works    value                   end-to-end criteria;
                                                  prioritize core
                                                  workflow

  License incompatibility Distribution or legal   Inventory and review
                          risk                    licenses before
                                                  bundling each
                                                  engine/library

  Large projects          Poor AI decisions or    Scoped resources,
  overwhelm AI context    expensive requests      pagination, summaries,
                                                  stable IDs,
                                                  revision-aware reads
  -----------------------------------------------------------------------

------------------------------------------------------------------------

## 13. Repository and documentation structure

A suggested initial repository layout:

``` text
/
├── README.md
├── CONTRIBUTING.md
├── SECURITY.md
├── LICENSE
├── docs/
│   ├── project-plan.md
│   ├── architecture/
│   │   ├── overview.md
│   │   ├── decisions/
│   │   ├── domain-model.md
│   │   ├── persistence.md
│   │   ├── mcp.md
│   │   └── engine-adapters.md
│   ├── requirements/
│   ├── testing/
│   └── user-guide/
├── schemas/
│   ├── project/
│   ├── component/
│   ├── design-rules/
│   └── mcp/
├── fixtures/
│   ├── projects/
│   ├── components/
│   ├── simulation/
│   ├── pcb/
│   └── interchange/
├── app/
│   ├── desktop-javafx/
│   ├── application-services/
│   ├── domain/
│   ├── persistence-json/
│   ├── component-library/
│   ├── schematic/
│   ├── validation/
│   ├── simulation/
│   ├── pcb-adapter/
│   ├── geometry-adapter/
│   ├── interoperability/
│   └── mcp-server/
├── workers/
│   └── [isolated engine workers, if needed]
└── build/
    └── [CI and packaging scripts]
```

This is a logical proposal, not a mandate to create every module
immediately. Start with a buildable structure and split modules when
ownership and dependency boundaries are understood.

------------------------------------------------------------------------

## 14. First milestone: detailed implementation sequence

The first milestone should prove the architecture through a small, real
workflow.

1.  **Bootstrap**
    -   Create repository, license, README, contribution guide, ADR
        template, build/test commands.
    -   Add Windows/Linux CI and dependency/license reporting.
2.  **Domain and schema**
    -   Define project, sheet, component definition/instance, pin, net,
        and diagnostic IDs.
    -   Add JSON schema validation and schema versioning.
    -   Create a minimal valid fixture project.
3.  **Persistence**
    -   Implement create/open/save/reopen.
    -   Add deterministic serialization, atomic replacement, and
        malformed-file diagnostics.
4.  **Application services**
    -   Implement project queries and commands for placing components,
        connecting pins, and validating.
    -   Add revision tracking and stale-revision rejection.
5.  **Schematic workspace**
    -   Render one sheet, place a few library components, connect pins,
        show net labels, and navigate diagnostics.
    -   Keep drawing state separate from connectivity identity.
6.  **MCP read-only**
    -   Add local stdio transport, schema-validated tools, project
        summary/structure, component search, net query, diagnostics, and
        capability discovery.
    -   Test with an independent MCP client.
7.  **Controlled MCP mutation**
    -   Add a preview/apply workflow for one simple edit.
    -   Ensure UI and MCP use the same service path.
8.  **Acceptance and packaging**
    -   Run the Gherkin-derived tests on Windows and Linux.
    -   Package a test build, document known limitations, and
        demonstrate the complete vertical slice.

### First-milestone demonstration

``` mermaid
sequenceDiagram
    actor User
    participant UI as JavaFX UI
    participant App as Application Services
    participant Model as Domain Model
    participant JSON as JSON Repository
    participant AI as MCP Client

    User->>UI: Open/create project
    UI->>App: CreateProject / OpenProject
    App->>JSON: Load or initialize
    JSON-->>App: Project state
    App-->>UI: Project summary and revision
    User->>UI: Place and connect components
    UI->>App: Commands with expected revision
    App->>Model: Validate and apply
    Model-->>App: Change set + new revision
    App-->>UI: Updated state and diagnostics
    AI->>App: Read project summary / net
    App-->>AI: Structured result and revision
    AI->>App: Preview proposed edit
    App-->>AI: Change preview + validation findings
    AI->>App: Apply preview with expected revision
    App->>Model: Revalidate and commit
    App-->>AI: Resulting revision and changed IDs
    User->>UI: Save project
    UI->>App: SaveProject
    App->>JSON: Atomic save
    JSON-->>App: Save result
    App-->>UI: Success or recovery-aware error
```

------------------------------------------------------------------------

## 15. Open decisions and decision records

The following decisions must remain open until evidence is collected:

  ---------------------------------------------------------------------------
  Decision                Current position            Required evidence
  ----------------------- --------------------------- -----------------------
  PCB engine              Undecided;                  Benchmark, API review,
                          established/custom/hybrid   license, OS coverage,
                          all remain candidates       round-trip tests

  Simulation engine       Hybrid adapter strategy     Model coverage,
                                                      convergence, licensing,
                                                      portability, automation

  Rust usage              Optional and selective      Profile or safety need;
                                                      FFI/process comparison;
                                                      packaging spike

  3D geometry kernel      Evaluate options including  License, formats,
                          Open CASCADE Technology     collision operations,
                                                      packaging, accuracy

  Native file packaging   JSON canonical;             Atomicity, asset
                          archive/container may       portability,
                          bundle assets               diffability, recovery

  macOS release           Keep architecturally        CI/package spike and
  commitment              feasible; Windows/Linux     product demand
                          first-class initially       

  Firmware simulation     Advanced phase              Supported MCU
                                                      ecosystem, licensing,
                                                      toolchain, sandboxing

  KiCad compatibility     Candidate interoperability  Feature mapping,
                          target, not assumed engine  version compatibility,
                          API                         round-trip fixtures
  ---------------------------------------------------------------------------

Each decision should have an ADR with: context, options, criteria,
evidence, decision, consequences, rejected alternatives, and revisit
conditions.

------------------------------------------------------------------------

## 16. Immediate next actions

1.  Create the repository and add this plan as `docs/project-plan.md`.
2.  Agree on the license and third-party dependency policy before
    bundling engines.
3.  Create ADRs for the canonical domain model/JSON format,
    application-service boundary, MCP security model, and cross-platform
    targets.
4.  Implement `ARC-01`, `ARC-02`, `ARC-03`, `MCP-01`, and `MCP-02` as
    the first technical foundation work.
5.  Build the minimal project fixture and automate `PRJ-001`, `SCH-001`,
    `SCH-002`, `MCP-001`, and `MCP-003`.
6.  Run small, time-boxed simulation and PCB engine evaluations early
    enough to prevent downstream architecture from assuming unverified
    capabilities.
7.  Do not begin a broad component-library import or a large custom PCB
    implementation until the canonical model, identity rules, and
    adapter strategy have been validated.

------------------------------------------------------------------------

## 17. Glossary

-   **Canonical model:** authoritative in-memory domain representation
    persisted in the native project.
-   **Derived state:** caches or engine-specific representations rebuilt
    from the canonical model.
-   **MCP:** Model Context Protocol, used to expose discoverable
    tools/resources/prompts to AI clients.
-   **Net:** an electrically connected group of pin instances with
    stable identity.
-   **DRC:** design-rule checking, such as PCB clearance and width
    checks.
-   **Capability declaration:** machine-readable statement of what an
    installed engine, component, adapter, or project can support.
-   **ADR:** Architecture Decision Record documenting a consequential
    technical decision.
-   **Revision:** monotonic project version used for change tracking and
    optimistic concurrency.
-   **Clearance envelope:** simplified safety volume around a part,
    distinct from its actual solid geometry.
-   **Round-trip test:** export and re-import a supported subset, then
    compare its defined semantics.
