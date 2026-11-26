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

## R0b: complete the foundation release gate

1. Add automated JavaFX user journeys (create/place/wire/simulate/route/save/
   reopen) on Windows and Linux; manually validate accessibility and rendering.
2. Exercise an independent production MCP client on each supported OS. The
   existing wire-level protocol peer is a contract test, not that acceptance gate.
3. Add persisted wire graphics and junctions, undo/redo, multi-select and
   copy/paste. Keep electrical identity separate from drawing geometry.
4. Add autosave/recovery UX, asset manifests, general extension JSON, and
   explicit import compatibility reports.
5. Bind MCP approvals to specific previews, persist change events, and add
   per-user identities and richer audit records.
6. Add library authoring, more parts (diodes, ICs) and explicit library
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
| PRJ-001/002 | JSON round-trip, 0.1→0.2 migration, H2 reopen, future-schema rejection, desktop session open/save/conflict tests | GUI reopen acceptance |
| PRJ-003 / XPL-002 | Atomic file save, retained backup, Unicode/spaced paths, dirty tracking | Interrupted-save fault injection, recovery UX |
| SCH-001/002/004 | Change-set placement/move/wire/delete, validation purity | Automated GUI journey |
| SCH-003 | Co-located components never infer connectivity | Persisted wire graphics/junctions |
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
