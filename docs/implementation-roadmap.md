# Implementation roadmap

The 1,806-line product plan is the product baseline, not a claim that every
capability is implemented. This roadmap records the initial implementation scope
without changing that user-authored plan.

## R0a: implemented technical foundation

- [x] Maven reactor with separate domain/DTO, feature logic, persistence,
  shared HTTP API, web controllers, OpenFeign connector, server, desktop, and MCP.
- [x] Spring dependency injection in all runtime composition roots.
- [x] Desktop local-server startup/readiness/ownership and explicit remote mode.
- [x] Version `0.1` canonical JSON, strict decoding, published schema and fixture.
- [x] Project/sheet/component/pin/net identities and pinned definition snapshots.
- [x] Required-pin diagnostics; explicit net membership and deterministic merging.
- [x] H2 file storage, PostgreSQL configuration, Flyway, atomic revision checks.
- [x] JavaFX creation/reopening, placement, pin selection, connectivity guides,
  validation, asynchronous requests, JSON import/export with backup.
- [x] Ten read-only SDK-based stdio MCP tools through the same server API.
- [x] Owner/reader credentials, loopback defaults, strict remote HTTPS connector.
- [x] Windows/Linux Java 21/25 CI definitions and opt-in PostgreSQL contract.
- [x] Release workflow on `v*` tags: headless server and MCP JARs, per-OS
  client + embedded local-server JARs (`bundle-local-server` profile), a
  jpackage Windows portable zip smoke-tested in capture mode, a smoke-tested
  multi-arch server Docker image on GHCR, and checksums.
- [x] Zero-configuration local runtime: generated owner token in the data
  directory and embedded server extraction, both covered by `LocalServerTest`.

## R0c: implemented all-parts vertical slice (schema 0.2)

- [x] Eleven built-in kinds (R, L, C, DC source, power rail, ground, SPST switch,
  NPN, PNP, NMOS, PMOS) with parameters, footprints and 3D bodies.
- [x] Schema 0.2 with board data; 0.1 documents migrate on read preserving IDs.
- [x] One transactional change-set engine (16 operation types, preview/apply,
  readable selectors) shared by desktop, REST and MCP.
- [x] Electrical rule checks per kind (floating gates/bases, shorted parts,
  missing ground/source).
- [x] MNA simulator: DC operating point and transient with switch events.
- [x] Logic: virtual gates (AND/OR/NAND/NOR/XOR/NOT), constant and clock sources,
  sample 74HC00/04/08/32/86/74 DIP-14 ICs with footprints and bodies; behavioural
  analog models, event-driven four-state `logic` analysis, logic ERC, desktop
  symbols and timing diagram, MCP `simulation.run` with `analysis=logic`.
- [x] PCB: sync, placement, two-layer orthogonal routing, ratsnest, DRC.
- [x] 3D assembly: parametric bodies, collisions/overhang, JavaFX 3D viewer with
  Oracle's camera rig and an orthographic ParallelCamera; STL/OBJ overlay import behind a `ModelImporter` interface.
- [x] Desktop: tabbed schematic/simulation/PCB/3D editors; New/Open/Save/Save As
  with dirty tracking, conflict reload and file-vs-server choices on open.
- [x] MCP: 27 annotated tools gated by `McpPolicy` (read, edit, destructive).
- [x] Capture mode (`expanse.desktop.capture-dir`) for screenshot-based checks.

## R0d: implemented undo/redo, wire graphics and project parts (schema 0.3)

- [x] Undo/redo: every revision's document is kept (`project_revisions`, Flyway
  V2); undo/redo restore an earlier state as a new revision, so clients see it
  like any edit. REST `/history`, `/history/undo`, `/history/redo`; MCP
  `project.get_history`, `project.undo`, `project.redo`; desktop Edit menu.
- [x] Schema 0.3: nets carry drawn wires (pin or free ends plus bend points) and
  projects carry custom part definitions; 0.2 documents migrate losslessly.
- [x] Wire graphics: `draw_wire`/`delete_wire` operations, tees onto existing
  wires, wires kept through net merges and removed with their pins; desktop
  bends, orthogonal elbows, junction dots, wire selection/deletion and dashed
  lines for pins not yet reached by wires. Connectivity stays pin membership.
- [x] Part editor: `define_part`/`delete_part` operations (`PART_IN_USE`), MCP
  `parts.list`/`parts.define`; `generic` parts (box symbol, ignored by
  simulation with a warning) or parts compatible with a built-in kind's model;
  redefinition updates placed instances (pins by number, values kept when
  valid, board placement dropped if the part becomes virtual). Desktop dialog
  with pins/parameters/pads text forms, DIP/SIP pad generator, courtyard and 3D
  body; project parts lead the palette.
- [x] Change engine now has 20 operation types; MCP has 33 tools.

## R0e: implemented tooling, health and settings

- [x] Lombok (`@Slf4j`, `@RequiredArgsConstructor`, fluent `@Getter`) and a
  MapStruct `DesignViewMapper` replacing hand-written domain-to-DTO mapping;
  processors configured in the parent POM, Lombok excluded from runtime jars.
- [x] Server Actuator: anonymous `/actuator/health` (status only, liveness and
  readiness groups) and `/actuator/info`; all other actuator endpoints denied.
- [x] springdoc-openapi: `/v3/api-docs` and `/swagger-ui.html` with the bearer
  scheme and a discriminator mapping for every change-set `op`.
- [x] Desktop health dot (5 s polling, tooltip, click for settings) and
  *File > Settings* (mode, URL, token, data directory) persisted to
  `settings.properties` below CLI/env precedence, with in-place reconnect and
  rollback, and a settings prompt instead of a failed launch.

## R0f: implemented editing UX, more parts and GUI journeys (schema 0.4)

- [x] Diode, LED and op-amp parts in the library, simulator (Shockley diode/LED,
  tanh-limited open-loop op-amp to its rails), validation, symbols,
  footprints and 3D bodies.
- [x] Schema 0.4 net labels: `place_net_label`/`delete_net_label` operations
  (22 operation types), MCP `schematic.place_net_label` (34 tools), 0.3
  migration; desktop Label tool, label flags, rename/delete.
- [x] Desktop multi-select (box, Shift/Ctrl-click, Select all), group
  move/rotate/delete, cut/copy/paste/duplicate keeping internal nets, labels and
  wires with renumbered references; Space/middle-drag pan.
- [x] Automated TestFX/Monocle GUI journeys against the real local server:
  build/simulate/save/reopen, undo/redo, net labels, group editing.

## R0b: complete the foundation release gate

1. Extend the GUI journeys to PCB routing and 3D (schematic journeys exist) and
   manually validate accessibility and rendering.
2. Exercise an independent production MCP client on each supported OS. The
   existing wire-level protocol peer is a contract test, not that acceptance gate.
3. Add graphical symbol/footprint drawing and hierarchical labels (wires,
   net labels, multi-select, copy/paste, undo/redo and form-based part editing
   exist).
4. Add autosave/recovery UX, asset manifests, general extension JSON, and
   explicit import compatibility reports.
5. Bind MCP approvals to specific previews, persist change events, and add
   per-user identities and richer audit records.
6. Add library authoring, more parts (Zeners, regulators, ICs) and explicit library
   migration previews.
7. Introduce server-side component/net pagination and caching keyed to revision
   once project sizes warrant it.
8. Produce signed native installers, full license/SBOM review, release notices,
   source provenance, and macOS feasibility evidence (unsigned portable/JAR
   releases exist).

No R0 release claim should be made before these applicable exit criteria are
verified on both first-class platforms.

## Acceptance evidence

| Plan IDs | Current executable coverage | Remaining gap |
| --- | --- | --- |
| ARC-001 | Headless domain/application tests; client dependency bans | Broader architecture rules as modules grow |
| PRJ-001/002 | JSON round-trip, 0.1→0.4 migration, undo/redo history, H2 reopen, future-schema rejection, desktop session open/save/conflict tests, GUI save/reopen journey | Remote-mode GUI journey |
| PRJ-003 / XPL-002 | Atomic file save, retained backup, Unicode/spaced paths, dirty tracking | Interrupted-save fault injection, recovery UX |
| SCH-001/002/004 | Change-set placement/move/wire/delete, validation purity, GUI journeys for place/wire/group move/copy/paste/delete/undo | Accessibility review |
| SCH-003 | Co-located components never infer connectivity; wires and net labels drawn/merged/cleaned up with nets, 0.2→0.3→0.4 migration, GUI net-label journey | Hierarchical labels |
| LIB-002 | Exact definition snapshot/version round-trip | Library update/migration workflow |
| VAL-001/002 | Structured stable findings per part kind; unchanged revisions | More electrical rules |
| SIM (R1) | Reference circuits: dividers, RC/RL transients, switch events, BJT/MOS operating points | Engine evaluation vs ngspice/Xyce, cancellation |
| PCB (R2) | Sync/place/route, ratsnest, shorts/clearance/outline DRC; DRC-clean fixture | Autorouting, fabrication exports |
| 3D (R3) | Body derivation, collisions/overhang, STL/OBJ importer tests | STEP import, true solid clearance |
| MCP-001/005/006 | Stdio handshake, discovery under each policy, annotations, operation schema | Real-client OS acceptance |
| MCP-003 | Shared change engine, stale-revision rejection, reader token cannot apply | Preview-bound approvals |
| XPL-001 | CI configuration for Windows/Linux and Java 21/25 | CI results must be observed after push |
| SEC-001 | Strict schema/size/identity checks and read-only token tests | General importers not yet implemented |

The PostgreSQL test is skipped unless `expanse.postgres.tests=true`; enabling it
requires Docker and tests the same Flyway migration and compare-and-swap adapter.
The Linux/Java 21 CI job enables it. A skipped test is not PostgreSQL verification.

## R1-R4: retain the product decision gates

R1 compares the in-house MNA engine against established engines (ngspice,
Xyce) on reference circuits, licensing, headless Windows/Linux execution,
provenance and cancellation, then decides whether to adopt one behind the
existing simulation port (AC, SPICE models, larger circuits).

R2 scores established, custom and hybrid PCB engines using the original
weighted criteria before adding autorouting, footprint libraries and
fabrication exports with loss reports.

R3 evaluates geometry kernels independently of rendering, then adds STEP import
and honest solid-vs-envelope clearance diagnostics on top of the current
parametric bodies and units/axes conventions.

R4 selects firmware, mixed-signal, autorouting and advanced analyses from actual
user demand. Rust remains conditional on measured performance or a concrete
safety/integration need. No empty feature modules or pretend engine adapters are
introduced ahead of these decisions.
