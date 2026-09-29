# NATEN Mobile — Master Build Instructions

## Mission

Build NATEN into a real Android-first workflow automation platform that feels and behaves like a serious mobile counterpart to n8n.

The app must never be an empty visual shell. Every visible control must have a real state change and/or runtime consequence.

The user has no requirement for desktop ownership or a private server. NATEN must run workflows on the Android phone itself wherever Android permits, including background execution when the app UI is closed.

## User requirements to preserve

- Android phone is the primary computer for NATEN.
- No laptop is required to create, save, activate, debug, import, export, or run workflows.
- The visual workflow builder must be node-based and comparable in concept to desktop n8n.
- Nodes must be real and configurable, not decorative.
- Connections must be real graph edges.
- Run must actually execute the graph.
- Active workflows must continue in the background.
- Scheduled workflows must run with the screen off and the UI closed where Android permits.
- Webhook-based workflows must be able to stay available through a deliberate Android foreground automation mode.
- Long-running/background execution must not depend on the Activity remaining open.
- Build/test/fix/build again before handing over an APK.
- Do not claim unsupported features are complete.
- The immediate goal is feature completeness and reliability; visual polishing comes afterward.

## n8n parity target

Use current n8n behavior as the architectural reference: n8n has a large node ecosystem, trigger varieties, executable nodes, declarative nodes, credential systems, error handling, sub-workflows, AI capabilities, and many application integrations.

NATEN should target:

1. High semantic compatibility for common workflow structures.
2. A broad searchable node catalog.
3. A generic API/HTTP path for services without a dedicated adapter.
4. n8n-style JSON import/export where semantics are known.
5. An adapter architecture so more integrations can be added without rewriting the engine.

Do not copy n8n's Node.js runtime into Android. Reimplement the workflow semantics natively.

## Current platform architecture

### 1. Workflow storage

Every workflow has:
- stable ID
- name
- active/inactive state
- nodes
- edges
- branch labels
- node configuration
- execution history

Multiple workflows must coexist and remain independently activatable.

### 2. Node model

Every node definition must contain:
- stable type
- category
- display title
- description
- configuration schema
- runtime executor
- trigger classification when applicable
- input/output expectations
- documentation/help text
- supported/unsupported state

An unsupported imported n8n node must be preserved and clearly marked. Never silently execute it as a no-op.

### 3. Execution engine

The runtime must support:
- manual execution
- schedule execution
- webhook execution
- graph traversal
- branches
- data passing
- expressions
- variables
- retries
- timeout handling
- continue-on-fail behavior
- per-node status
- execution logs
- final output
- error propagation
- execution history
- cancellation where possible

Use a stable internal execution context rather than passing ad-hoc strings between nodes.

### 4. Data model

Work toward an item-oriented data model similar to n8n:
- one or more JSON items
- binary/file references
- current item
- full input items
- workflow variables
- execution metadata

Core data nodes must operate on collections, not only a single JSON object.

### 5. Expression system

Support practical expressions such as:
- JSON field lookup
- nested JSON paths
- workflow variables
- current timestamp
- simple string interpolation
- common comparisons
- safe arithmetic/basic transforms

Expressions must be parsed consistently across nodes.

## Node families to implement

### Triggers
- Manual Trigger
- Schedule Trigger
- Webhook Trigger
- Chat Trigger
- Error Trigger
- polling/event triggers as Android capability allows

### Flow
- IF
- Switch
- Filter
- Merge
- Loop Over Items
- Wait
- Stop/Error
- Execute Sub-workflow
- Respond to Webhook

### Data transformation
- Edit Fields
- Limit
- Remove Duplicates
- Rename Keys
- Sort
- Split Out
- Summarize
- JSON Parse
- JSON Stringify
- Date & Time
- Code/safe transform
- Markdown
- HTML
- XML
- Crypto

### Files
- Read File
- Write File
- Convert to File
- Extract From File
- binary/file metadata

### Connectivity
- HTTP Request
- Generic API
- GraphQL
- pagination
- headers/query/body
- auth modes
- retry/timeout controls

### Android
- Notification
- Open URL
- Share Text
- background automation controls
- file picker/import/export
- optional future device-action adapters subject to Android permission rules

### AI
- AI Text
- AI Agent
- model/provider credentials
- structured output
- tool calls
- memory
- embeddings
- vector retrieval
- MCP
- provider fallback

### Integrations

Start with broad API-backed adapters, then add native operation panels and OAuth:
- Gmail
- Google Sheets
- Google Drive
- Telegram
- Slack
- Discord
- GitHub
- Notion
- Airtable
- Trello
- WordPress
- Shopify
- Stripe
- Calendar
- RSS
- databases
- email/SMTP

The catalog should grow toward the current n8n application ecosystem instead of pretending a finite first release contains every integration.

## Background automation — mandatory

Background automation is not optional.

### Schedule workflows

Use Android scheduling to wake the app without the UI:
- active Schedule Trigger -> scheduled wake-up
- wake-up -> load exact workflow ID
- execute workflow in background
- persist result
- show completion/failure notification
- schedule next run

Do not use a permanent busy loop.

### Webhook workflows

For explicitly enabled webhook workflows:
- keep a foreground automation service alive
- show a persistent status notification
- listen on the local interface
- validate path/method/request size
- execute matching workflow
- return configured webhook response

The service must stop automatically when no webhook workflow requires it.

### Boot/update recovery

On device boot and app replacement:
- reload active workflows
- restore schedules
- restart webhook mode only when active workflows require it

### Android restrictions

Design around Android's background and foreground-service restrictions rather than trying to bypass them.

The UI must explain when:
- battery optimization may delay automation
- manufacturer task killers may interfere
- notification permission is disabled
- a foreground service is required
- an OS restriction makes a requested trigger impossible

## Credentials and security

Use the Android Keystore-backed credential vault.

Credential types to support progressively:
- API key
- bearer token
- basic auth
- custom header
- OAuth2
- service account where feasible

Rules:
- never write secrets into logs
- never include secrets in ordinary execution history
- mask secret fields
- warn before exporting workflows containing embedded secrets
- prefer credential references over raw secrets in node configs
- prevent path traversal in file nodes
- keep webhook server intentionally scoped

## Mobile editor requirements

The editor should become a true mobile workflow IDE:

- infinite-ish pan/zoom canvas
- pinch zoom
- two-finger pan
- node drag
- connection drag
- branch labeling
- multi-select
- delete
- duplicate
- copy/paste
- undo/redo
- snap/grid options
- auto-layout
- minimap
- searchable node library
- recent/favorite nodes
- node execution badges
- input/output inspector
- test data
- execution data per node

## Workflow operations

Support:
- new
- rename
- duplicate
- delete
- activate/deactivate
- export
- import
- save
- execution history
- retry last execution
- clear execution history
- templates

## Import/export

Native format:
- NATEN workflow JSON

Compatibility format:
- n8n-style workflow JSON

Rules:
- preserve node names, IDs, positions, parameters, and connections when possible
- clearly mark unsupported nodes
- offer fallback to Generic API where useful
- never silently change a workflow and call it compatible

## Execution reliability

Each execution must have:
- run ID
- start/end timestamps
- duration
- workflow ID
- node-by-node status
- node inputs where safe
- node outputs where safe
- error message
- retry count
- background/foreground source

Add:
- timeout
- retry policy
- exponential backoff
- continue-on-fail
- cancellation
- safe size limits

## Testing loop

Every implementation pass must follow:

1. Inspect current repo and preserve working functionality.
2. Make one coherent architectural change.
3. Compile with GitHub Actions.
4. Read the actual build logs.
5. Fix every compile/package error.
6. Rebuild.
7. Verify the artifact exists.
8. Inspect the APK/archive.
9. Only then continue to the next layer.
10. Hand over the APK only from a successful build.

Never stack duplicate runtime files or duplicate methods.
Never replace a working engine with an uncompiled draft.
Never present a failed build as a finished APK.

## Definition of real functionality

A feature is complete only when:

UI -> persisted configuration -> runtime executor -> observable result -> error handling -> build verification.

A button that only changes animation is not a feature.

## Execution phases

### Phase 1 — Stabilize the platform
- clean duplicated/broken runtime
- compile cleanly
- preserve current working multi-workflow, node library, credentials, webhook and background capabilities
- establish deterministic execution context

### Phase 2 — Deep execution engine
- item-oriented data
- proper loops/batches
- retries
- timeouts
- cancellation
- continue-on-fail
- execution inspector
- sub-workflows
- webhook responses

### Phase 3 — Background reliability
- robust schedule wakeups
- foreground webhook mode
- boot/update restoration
- automation status screen
- battery/power restriction diagnostics
- notification controls

### Phase 4 — Integration platform
- credential types
- OAuth2
- API adapters
- pagination
- rate-limit handling
- major service integrations
- import/export improvements

### Phase 5 — AI platform
- AI agent
- tools
- structured outputs
- memory
- MCP
- embeddings/vector retrieval
- model fallback

### Phase 6 — Mobile IDE
- zoom/pan
- minimap
- selection/editing
- undo/redo
- auto-layout
- inspector
- templates
- workflow search

### Phase 7 — Release hardening
- crash/error handling
- migration tests
- background reliability tests
- APK install verification
- final smoke workflows
- then visual polish

## Immediate acceptance test

The next APK must be able to demonstrate, on an Android device:

Manual -> Edit Fields -> HTTP Request -> IF -> Notification/Log

Schedule -> HTTP Request -> Notification

Webhook -> HTTP Request -> Respond to Webhook

and must retain workflow state after the UI is closed.

The result of these workflows must be visible in execution history.

## Product truth

NATEN is intended to become a mobile-first automation platform, not a skin around n8n.

Current n8n contains a very large and changing integration ecosystem. NATEN should progressively cover that ecosystem through native adapters, generic API support, and compatible workflow import/export rather than falsely claiming one APK implements every n8n node immediately.
