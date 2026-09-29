# NATEN Mobile — Execution & Completion Plan

## Product target

NATEN is being developed as a mobile-first workflow automation platform inspired by the n8n node/connection model.

The goal is not to create a decorative n8n-like screen. A workflow must be executable, persistent, debuggable, importable/exportable, and able to continue running when the app UI is closed.

## Non-negotiable requirements

- Android-first and usable from a phone.
- No dependency on the user owning a laptop or server.
- Workflows execute locally on the phone.
- Background automation is a first-class feature.
- Schedule-based workflows continue after the app UI closes where Android permits.
- Webhook/chat workflows can keep an Android foreground automation service.
- Node buttons and controls must perform real actions.
- Every node added to the canvas must have configurable data and a defined runtime behavior or a clearly exposed generic API fallback.
- Save, restore, import, export, execution history, errors, and logs must work.
- The canvas must support real node-to-node connections rather than animation-only lines.
- Build must be validated by GitHub Actions before an APK is handed over.
- Do not claim a feature is implemented unless its runtime path exists.

## n8n capability target

Current n8n documentation groups capabilities around triggers, flow logic, working with data, built-in core nodes, AI functionality, credentials, error handling, sub-workflows, and a very large application integration catalogue.

NATEN therefore uses three layers:

1. Core runtime nodes implemented directly on Android.
2. Generic HTTP/GraphQL/API nodes that can connect to services without a bespoke mobile SDK.
3. Dedicated adapters added progressively for high-value services.

This keeps the APK practical while preserving broad interoperability.

## Runtime architecture

### Workflow model

- Workflow identity and name.
- Nodes with position, type, title, configuration.
- Directed edges.
- Branch labels for conditional nodes.
- Active/inactive state.
- Persistent per-workflow storage.

### Execution engine

- Trigger resolution.
- Graph traversal.
- Data passing between nodes.
- Expressions and variables.
- Per-node execution status.
- Failure propagation.
- Execution history.
- Background execution mode.
- Webhook request input.

### Background automation

Use Android-native scheduling plus foreground execution:

- Schedule Trigger -> Android alarm -> foreground AutomationService -> WorkflowEngine.
- Active webhook/chat workflows -> persistent AutomationService + local HTTP listener.
- Boot/package replacement -> active schedules are restored.
- Avoid a permanently spinning background loop for ordinary schedules.
- Do not rely on UI process lifetime for automation.

Android 12+ restricts arbitrary background foreground-service starts, and Android 14+ requires foreground-service types. NATEN's always-on automation uses the documented specialUse category with an explicit service use-case declaration. Android 15 also places time limits on data-sync/media-processing foreground services, so those types are not used as a generic automation workaround.

## Core node families

### Triggers

Manual, Schedule, Webhook, Chat, Error, and later event/polling triggers.

### Flow logic

IF, Filter, Switch, Merge, Loop Over Items, Wait, Stop/Error, Execute Sub-workflow.

### Data

Edit Fields, Limit, Remove Duplicates, Rename Keys, Sort, Split Out, Summarize, JSON Parse/Stringify, Date & Time.

### Content and utility

Code, Markdown, HTML, XML, Crypto, No Operation.

### Files

Read, Write, Convert to File, Extract From File.

### AI

AI Text, AI Agent, model provider gateway, later tool calling, memory, structured output, embeddings, retrievers, vector stores, and MCP.

### Connectivity

HTTP Request, Generic API, GraphQL.

### Android actions

Notification, Open URL, Share Text, background service control.

### Service adapters

Gmail, Google Sheets, Telegram, Slack, Email and more can begin as API-backed adapters and gain richer credentials/operations later.

## Compatibility strategy

NATEN should import/export an n8n-style workflow structure where practical.

Unknown n8n node types must not silently pretend to be supported. The importer should preserve the original node metadata and offer a Generic API or unsupported-node warning.

The long-term target is high semantic compatibility for common workflows, not binary compatibility with n8n's Node.js runtime.

## Bug-clearing loop

Every feature cycle follows:

1. Implement.
2. Commit.
3. GitHub Actions debug build.
4. Read compiler/runtime packaging errors.
5. Fix.
6. Rebuild.
7. Verify artifact exists.
8. Hand over APK only from a successful run.

## Engineering phases

### Phase A — platform foundation

- Multi-workflow storage.
- Searchable node library.
- Functional graph editing and connections.
- Expanded core runtime.
- Generic API/GraphQL.
- Background scheduling.
- Foreground automation service.
- Local webhook listener.
- Execution history.
- n8n-style import/export.

### Phase B — execution depth

- True multi-item data model.
- Loop/batch execution semantics.
- Retry policies.
- Per-node timeouts.
- Continue-on-fail/error branches.
- Execution data inspector.
- Manual input/test data.
- Credential vault using Android Keystore.
- Better webhook responses.
- Sub-workflows.

### Phase C — integration platform

- OAuth2.
- API key/basic auth credential types.
- Reusable credentials.
- Gmail.
- Google Sheets.
- Telegram.
- Slack.
- Discord.
- Notion.
- Google Drive.
- GitHub.
- Airtable.
- Trello.
- WordPress.
- Shopify.
- Stripe.
- Calendar.
- RSS.
- Database/API connectors.

### Phase D — AI automation

- AI Agent with tools.
- MCP client/server support.
- Structured output.
- Memory.
- Embeddings.
- Vector stores.
- Human approval steps.
- AI tool-call execution.
- Model switching/fallback.
- Local-model adapters where practical.

### Phase E — mobile UX parity

- Infinite canvas.
- Pinch zoom/pan.
- Minimap.
- Node groups.
- Multi-select.
- Copy/paste.
- Undo/redo.
- Keyboard support for tablets.
- Node execution badges.
- Input/output data panels.
- Search across workflows.
- Templates.
- Import from file/share sheet.
- Mobile-optimized credential editor.

## Background reliability requirements

Automation must continue with the screen off and the app UI closed where Android permits it.

The app must surface when Android battery optimization, manufacturer restrictions, missing notification permission, or OS policy can affect automation.

For always-on webhook use, the foreground notification is intentional and must show that automation is active.

## Security requirements

- API keys and credentials must never be logged.
- Credentials should move to Android Keystore-backed secure storage.
- Export should warn when secrets are included.
- Webhook server must validate paths and request sizes.
- Prevent path traversal in file nodes.
- Do not expose private app files to arbitrary network clients.
- Restrict local server behavior to explicitly enabled workflows.

## Definition of real

A feature is complete only when:
- its UI control changes persisted state,
- the engine consumes that state,
- execution produces a real observable result,
- failures are surfaced,
- and the build contains that code path.

Animation-only controls are explicitly out of scope.

## Project handoff rule

Do not stop at a visual milestone. Continue through implementation, compile/build validation, bug fixing, and successful APK generation before presenting the build as ready for the next testing cycle.
