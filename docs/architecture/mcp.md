# MCP and security boundary

The `expanse-mcp` executable uses the official MCP Java SDK, not a custom
JSON-RPC implementation. Its only transport is stdio. The SDK negotiates the
stdio protocol (currently `2024-11-05` in the pinned SDK); capabilities must not
claim support for newer protocol features merely because the SDK contains other
transport implementations.

Every tool calls the same authenticated REST interface used by JavaFX.
Controllers delegate to shared application services. There are no direct
database, filesystem, shell, or engine operations in the bridge.

Tools are registered according to a policy (`McpPolicy`): read tools are always
present; editing tools need `expanse.mcp.allow-mutations=true` (default); delete
tools and destructive operations inside change sets need
`expanse.mcp.allow-destructive=true` (default false). Each tool carries MCP
annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
`openWorldHint=false`) so clients can ask for approval appropriately.

| Kind | Tools |
| --- | --- |
| Read (18) | design.get_capabilities, project.list, project.get_summary, project.get_snapshot, schematic.list_components, schematic.get_component, schematic.list_nets, schematic.get_net, library.search_components, parts.list, design.get_violations, simulation.run, pcb.get_board, pcb.get_analysis, assembly.get_scene, assembly.check, project.get_history, project.preview_changes |
| Edit (13) | project.create, project.apply_changes, schematic.place_component, schematic.move_component, schematic.connect_pins, schematic.set_properties, schematic.draw_wire, parts.define, pcb.sync_board, pcb.place_footprint, pcb.route, project.undo, project.redo |
| Destructive (2) | schematic.delete_component, project.delete |

Tool input schemas are generated with the registrations and reject unknown
arguments. Components, pins and nets accept UUIDs or readable selectors (`R1`,
`Q1.B`, net names). `project.preview_changes`/`project.apply_changes` take an
`operations` array whose `oneOf` schema is derived from the shared
`ApiModels.Operation` subtypes, so REST, desktop and MCP cannot drift. Every edit
requires `expectedRevision`; a stale revision fails with `REVISION_CONFLICT` and
nothing is applied. Change results omit the full snapshot unless
`includeSnapshot` is true; `simulation.run` (`op`, `transient` or `logic`)
down-samples waveforms and truncates logic transition lists (`maxPoints`,
default 200), naming truncated nets in the warnings. Project-derived responses include the source revision. Errors use
`isError` with stable codes: `POLICY_DENIED`, `INVALID_ARGUMENT`,
`SERVER_UNAVAILABLE`, or the server's API error code.
Wires are drawing objects: `schematic.draw_wire` (and the `draw_wire` operation)
draws a polyline from a pin or a free point on a net to a pin or a free point,
connecting the end pins as a side effect, so connectivity is always the net's pin
membership. `parts.define` creates or updates a project part (`project.` ID
prefix): `generic` parts are boxes ignored by simulation, while parts of a
built-in kind must keep that kind's pin numbers and parameters and simulate like
it. Redefining a placed part updates its instances in the same revision;
`delete_part` (destructive) fails with `PART_IN_USE` while instances exist.
`project.undo`/`project.redo` restore earlier document states as new revisions,
so they also need `expectedRevision`; `project.get_history` reports what can be
undone or redone.

## Credentials and deployment

- The REST server binds to loopback by default and requires an explicitly
  configured owner token; there is no default password or unauthenticated API.
- In local mode without a configured token, the desktop generates a random
  64-hex owner token once, stores it in `<data dir>/local-api-token` (owner-only
  permissions on POSIX) and passes it to its server child. The headless server
  and the MCP bridge never generate tokens; copy this file's value to the bridge.
- An optional distinct reader token can perform GET requests, previews and
  simulations but cannot apply changes, import or delete. Give the bridge the
  reader token when the agent should only inspect.
- Both client launchers reject non-loopback HTTP endpoints; remote endpoints
  require HTTPS. Redirect following and automatic mutation retries are disabled.
- Credentials belong in environment variables or an external secret store, not
  project files, version control, or MCP tool arguments.
- Server logs identify caller role, action, project/revision where applicable,
  and mutation outcome without logging authorization headers or project contents.
  Logs are operational audit evidence, not an immutable compliance audit trail.
- MCP logs go to stderr, with the Spring banner disabled. Stdout is protocol only.
- Local mode stops only its own server child; it never terminates another
  application by process name. A pre-existing server is not owned by the desktop.

The current tokens identify **owner** and **reader**, not individual users.
There is no multi-tenant isolation, OAuth, token rotation UI, rate limiting, or
per-project ACL yet. Do not expose this development server directly to an
untrusted network. A remote deployment needs TLS termination, access controls,
protected DB backups, appropriate OS permissions on local state, and independent
deployment review.

## Mutations

Implemented safeguards: typed all-or-nothing change sets with preview, expected
revision checks (optimistic concurrency shared with the desktop), policy-gated
tool registration, destructive-operation flags, server-side authorization (the
reader token cannot apply), and operational audit logging of caller role,
project, revision and outcome.

Still open: approvals bound to a specific preview (today a client decides whether
to ask the user, guided by tool annotations), persisted change events and undo,
per-user identities, and an independent MCP client acceptance journey on Windows
and Linux. Cancellation/progress, resources, prompts and Streamable HTTP are not
implemented.