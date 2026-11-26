# Architecture: foundation decisions

Status: accepted for the initial slice; see the original `project-plan.md` for
the full product direction.

## ADR-001: client/server separation and composition

Unlike the reference applications' single-module Maven/Guice builds, Expanse
uses Maven modules and Spring composition roots. The reference group ID,
JavaFX desktop approach, and small runnable application convention are retained.
Java 21 is the minimum portable LTS target; development on Java 25 is supported.

| Module | Responsibility | Dependencies within the reactor |
| --- | --- | --- |
| expanse-domain | Immutable entities, identities, units, invariants | None |
| expanse-dto | Transport records and input constraints | None |
| expanse-library | Versioned built-in definitions (11 part kinds, pins, parameters, footprints, bodies) | Domain |
| expanse-schematic | Placement and explicit connectivity edits | Domain |
| expanse-validation | Pure, revision-specific electrical rule checks | Domain |
| expanse-simulation | MNA circuit simulator (DC operating point, transient) and event-driven logic simulator | Domain |
| expanse-pcb | Board editing, pad geometry, orthogonal routing, ratsnest and DRC | Domain |
| expanse-assembly | Parametric 3D bodies from the board and mechanical checks | Domain, PCB |
| expanse-application | Use cases, change-set engine, repository port, canonical JSON codec and migration | Feature modules, DTO |
| expanse-persistence | JDBC adapter and Flyway migrations | Application |
| expanse-api | Shared annotated HTTP interfaces | DTO |
| expanse-api-web | Controllers and structured HTTP errors | API, application |
| expanse-connector | OpenFeign client, credentials, timeouts, errors | API |
| expanse-server | Spring Boot composition, HTTP security, DB configuration | Web adapter, persistence |
| expanse-desktop | JavaFX shell: schematic, simulation, PCB and 3D views; project session and file save/open; STL/OBJ import | Connector |
| expanse-mcp | Official Java SDK stdio server with policy-gated read/edit tools | Connector |

The desktop and MCP bridge have **no dependency on server implementation,
domain entities, application services, or database drivers**. Maven Enforcer
rejects backend dependencies in both clients. API interfaces are shared by
controllers and OpenFeign, but DTOs do not expose persistence classes.
Maven modules are classpath libraries initially, not JPMS modules.

Local topology:

```text
JavaFX + Spring client ----\
                           authenticated loopback HTTP -> Spring server -> H2 file
MCP stdio + Spring client -/                                  |
                                                          shared use cases
```

Remote topology uses exactly the same API and client, over HTTPS, with H2 or
PostgreSQL on the server. No local/remote domain implementation forks exist.
The server remains a modular monolith, not a set of independently deployed
feature services. The user's explicit client/server requirement takes precedence
over the original plan's illustrative in-process desktop composition.

Consequences: local mode has a small HTTP/process startup cost but actually
proves remote parity and avoids shipping server classes in the desktop. One
owner token authenticates a trusted user; a separate reader token can constrain
AI inspection. Multi-user authorization is not yet a product capability.

## ADR-002: canonical JSON inside a relational repository

JSON is the canonical design representation, not a serialization of normalized
JPA entities. A relational `projects` table stores ID, name, revision, and the
entire validated canonical JSON document as portable `TEXT`. The relational
columns support identity, ordering, and atomic optimistic writes; they are not a
second independently editable model.

The JDBC adapter writes one aggregate in a single SQL statement:
`UPDATE ... WHERE id = ? AND revision = ?`. Exactly one concurrent writer wins.
No in-memory lock or last-writer-wins behavior is used. Reads always reconstruct
and validate the immutable aggregate. Initial JSON saves inherit database
transaction durability; filesystem export additionally uses a sibling temporary
file, backup, and atomic replacement. Unsupported atomic replacement is reported
as an error, never silently downgraded.

Flyway migration versions and JSON schema versions are separate. Schema `0.4`
is current: it adds net labels (on a pin or a free point) to nets; a label name
is the net's identity for connectivity, so placing a label with an existing
net's name joins that net. `0.3` documents migrate by adding empty label lists.
Schema `0.3` added drawn wires on nets (pin or free ends, bend points) and
project part definitions; `0.2` documents migrate by adding empty lists. Schema
`0.2` added typed definitions (kind, parameters, footprint, body),
component properties and rotation, and the board (outline, rules, placements,
traces). `0.1` documents are migrated on read by mapping the old built-in
definitions to their current versions while preserving every identity; unknown
definitions fail with `MIGRATION_FAILED` and nothing is written. Newer or unknown
versions are rejected. Unknown top-level fields, duplicate JSON
keys, missing fields, trailing content, and malformed identities are rejected.
Extension values are opaque **strings** in this first schema (they may contain
encoded JSON); they round-trip without interpretation. General arbitrary JSON
extensions, binary assets, and feature-gated sections need a future schema.

Limits: canonical JSON is limited to 8,388,608 characters; desktop file imports
also have an 8 MiB byte limit. HTTP Jackson parsing has nesting/string/document
limits. This aggregate approach is intentionally simple; do not mistake it for
efficient database querying of large component libraries. Add indexed relational
projections only when real query requirements justify them, keeping JSON
authoritative.

## ADR-003: immutable identity and electrical semantics

Projects, sheets, component instances, pins, and nets use UUIDs. Definition IDs
are namespaced library IDs plus explicit versions; instances embed an exact
definition snapshot. Names, references, array positions, and coordinates are not
electrical identities. Coordinates are explicit millimetres.

The initial editor places components on the main sheet. Pins belong to at most
one net. Connecting already-connected pins merges complete net memberships,
preserving the first existing net ID in project order. Duplicate references,
missing pins/sheets, duplicate IDs, and cross-sheet nets are rejected. Hierarchy
is not inferred. Validation is a pure query and does not advance revisions.
Every successful edit advances the project revision once and immediately
persists it. All edits (desktop, REST, MCP) are typed operations in a change set
that is previewed or applied atomically against an expected revision; there is
one change engine. Undo/redo restore a stored earlier document as a new
revision. Wires are drawing geometry only: `draw_wire` connects its end pins as a
side effect, and deleting a wire never changes connectivity.

## ADR-004: engines and native code

The first engines are small, in-house, pure-Java and deterministic, chosen so
every part kind works end to end before selecting heavyweight engines:

- Simulation: modified nodal analysis with Newton iteration; ideal R/L/C and DC
  sources, switches as on/off resistances, Ebers-Moll BJTs and level-1 MOSFETs,
  backward-Euler transient with switch events. Reports name the engine and models.
- Logic: a shared catalog (`Logic` in the domain) defines gate units and pinouts for
  virtual gates and 74HC ICs. Analog analyses model outputs as 50 ohm drivers to
  the IC supply with inputs thresholded at half supply. The `logic` analysis is
  event-driven with four levels (0/1/X/Z), inertial `propagationDelay`,
  unpowered ICs driving X, contention resolving to X, and switches/resistors
  mapped to strong/weak connections. ERC adds output conflicts, floating or
  undriven inputs and unpowered ICs.
- PCB: two-layer orthogonal routing between pads, ratsnest by minimum spanning
  tree, outline/short/clearance/courtyard checks. No autorouter or fabrication
  output.
- 3D: boxes and cylinders derived from footprint bodies; the desktop renders them
  with JavaFX 3D (camera rig after Oracle's "Building a 3D Sample Application"), using an orthographic `ParallelCamera` with zoom and depth compression on the camera so nothing clips.
  STL/OBJ files can be overlaid in the viewer through a `ModelImporter` adapter;
  a third-party importer (e.g. InteractiveMesh) can be added behind that
  interface. STEP and true solid geometry remain R3 work.

Engine selection for production (ngspice/Xyce, KiCad interop, OCCT) still
follows the plan's evaluation gates. Adapters belong behind application ports
and must not leak native types into the domain.

## Dependency and distribution policy

Direct versions are pinned by the parent POM, Spring Boot/Cloud BOMs, or explicit
properties. Main dependency families are Spring/Feign/Jackson/Flyway (Apache
2.0), JavaFX (GPLv2 with Classpath Exception), NetworkNT (Apache 2.0), H2 (MPL 2.0/EPL 1.0),
PostgreSQL JDBC (BSD-2-Clause), and MCP Java SDK (MIT). JUnit, AssertJ,
Mockito, and Testcontainers are test-only dependencies.
Lombok and MapStruct (MIT/Apache 2.0) are compile-time annotation processors
configured once in the parent POM (`annotationProcessorPaths`: Lombok, then
`lombok-mapstruct-binding`, then MapStruct). Lombok is `provided` scope and is
excluded from Boot jars; `lombok.config` keeps record-style fluent accessors and
the `LOG` logger name. MapStruct generates `DesignViewMapper` (domain records to
API views) with `unmappedTargetPolicy=ERROR`, so a new DTO field fails the build
until it is mapped. The server adds Spring Boot Actuator (anonymous health/info
only) and springdoc-openapi (Apache 2.0) for `/v3/api-docs` and Swagger UI.
These are initial family-level notes, not a completed redistribution review.

CI records the source revision in manifests and archives runtime dependency
inventories alongside platform-specific runnable JARs. Releases (`release.yml`,
tag `v*`) publish an OS-independent headless server JAR and MCP JAR, one desktop
JAR per OS with the server JAR embedded as a resource (not a dependency, so the
client classpath stays server-free and the enforcer rule still holds), and a
Windows jpackage app image zip with a jlinked runtime that keeps `java.exe` for
the local server child process, plus a multi-arch GHCR image of the headless
server built from the same verified jar (non-root, `/data` volume, binds
`0.0.0.0` only inside the container). Before publishing
installers, review the complete transitive inventory and ship required notices,
licenses, and native-library obligations. Review engines independently before
bundling. Signed native installers, a complete SBOM/license gate, and a macOS
app bundle remain open work.
