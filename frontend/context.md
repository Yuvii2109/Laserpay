# `frontend` (`pdei-web`) — module context

> Living document for the PDEI operator console. Read this before touching anything under
> `frontend/`. Normative sources that outrank this file: `docs/PLATFORM-CONTRACT.md` and
> `docs/SHARED-LIBRARY-API.md`.

---

## 1. Purpose

`pdei-web` is the human surface of the platform. It answers four questions and nothing else:

1. **Is this merchant's evidence ready?** (Control Tower, readiness bands, at-risk feed)
2. **What is missing, expired or contradictory, and on which transaction?** (gaps, evidence explorer)
3. **What happened to this dispute, and why did the platform decide what it decided?** (Case X-Ray:
   timeline, graph, evidence, AI reasoning, safety gate, package)
4. **Does the system still behave when things go wrong?** (simulation and chaos console, funnel)

It is a **read-mostly** console. The only writes it performs are the human decisions the platform
explicitly asks for: readiness recompute, evidence upload/verify, dispute creation, case
approve/reject/submit, policy versioning, and simulator/chaos control. It never edits financial
state directly, and it never sends anything to an AI service — AI lives in
`ai-reasoning-service` and reaches the UI only as stored, gated results.

---

## 2. Responsibilities

- Render every route in contract §14 inside one consistent shell.
- Own the **typed mirror** of the platform's DTOs and enums (`src/lib/types`), field-identical to
  the Java records listed in `docs/SHARED-LIBRARY-API.md` §4.
- Own **all** network access through one client (`src/lib/api/client.ts`) — contract §14 requires it.
- Keep server state in TanStack Query and UI state in Zustand, and keep them separate.
- Hold exactly one control-tower WebSocket per browser tab, and convert its frames into query
  invalidations rather than into direct state writes.
- Format money and time correctly, once, in one place.
- Remain fully explorable with the backend down (`NEXT_PUBLIC_USE_MOCKS=true`).

Explicitly **not** its responsibilities: business rules (readiness scoring, policy evaluation,
safety gating) live in `evidence-core`; the console displays their outputs and never re-derives
them. The one deliberate exception is `src/mocks`, which mirrors the readiness formula so the
fixture data is internally consistent — it is labelled as a mirror in the file.

---

## 3. Stack

Next.js 15 (App Router) · React 19 · TypeScript strict · Tailwind CSS 3.4 · shadcn/ui (Radix)
· TanStack Query v5 · Zustand 5 · Recharts 2 · lucide-react · date-fns 4 · zod 3 · sonner.

Node 20+. Package name `pdei-web` (contract §1). Host port 3000, health `/api/health` (contract §2).

---

## 4. Folder convention

```
frontend/
├── src/app/                  routes only (App Router). One folder per route in contract §14.
│   ├── layout.tsx            root layout: providers + AppShell
│   ├── providers.tsx         client providers (theme, query, tooltip, toaster)
│   ├── globals.css           design tokens (light + dark) and base styles
│   ├── page.tsx              `/` -> redirect to /control-tower
│   ├── loading.tsx           route-level skeleton
│   ├── error.tsx             route-level error boundary
│   ├── not-found.tsx         404 inside the shell, with the route map
│   └── api/health/route.ts   container health endpoint
├── src/components/ui/        shadcn primitives. Presentation only, no domain knowledge.
├── src/components/shared/    PDEI-aware building blocks. Domain-shaped, page-agnostic.
├── src/lib/types/            the typed mirror of the platform contract
├── src/lib/api/              client + one endpoint module per resource
├── src/lib/query/            QueryClient, key factory, WS-driven invalidation
├── src/lib/ws/               control-tower socket + frame parsing
├── src/lib/store/            Zustand stores (UI state, live state)
├── src/lib/format/           money, date, id, score formatting
├── src/lib/navigation.ts     the route map as plain (non-client) data
├── src/lib/config.ts         NEXT_PUBLIC_* configuration
├── src/lib/utils.ts          `cn` and small generic helpers
└── src/mocks/                deterministic fixtures + mock router + mock socket
```

**Rule for page folders:** a page owns only its own composition. Anything a second page could
want moves to `src/components/shared`. Anything that talks to the network moves to
`src/lib/api/endpoints`. Pages never call `fetch`, never build a URL, and never hand-write a
query key.

---

## 5. File-by-file map

### 5.1 App shell

| File | What it does |
|---|---|
| `src/app/layout.tsx` | Root layout. Injects the pre-paint theme script, mounts `Providers` and `AppShell`. `suppressHydrationWarning` is required because the script stamps the theme before hydration. |
| `src/app/providers.tsx` | `ThemeProvider` → `QueryProvider` → `TooltipProvider` + `Toaster`. |
| `src/app/globals.css` | Every design token for both themes, base styles, Recharts chrome, scrollbars. |
| `src/app/page.tsx` | `/` → `/control-tower` (contract §14). |
| `src/app/loading.tsx` / `error.tsx` / `not-found.tsx` | Route-level fallbacks. `error.tsx` surfaces the correlation id and Next's `digest`. |
| `src/app/api/health/route.ts` | `GET /api/health` → `{status, service, checkedAt, config}`. Reports the process, not the platform: it deliberately does not probe the gateway. |

### 5.2 Types (`src/lib/types`) — the contract mirror

| File | Contents |
|---|---|
| `common.ts` | `Money`, `PageResponse<T>` (`{content,page,size,totalElements,totalPages}` - mirrors `api.dto.PageResponse`), `SearchPage<T>` (`core.model.SearchPage`, parity only - no route returns it), `PageParams`, `ErrorResponseBody`, `ApiError`, `ActorType`, `ID_PREFIX`, `Iso8601`, `TimeRange`. |
| `events.ts` | `AggregateType`, `EventSource`, `EventType` (all 30, exact spelling), `EVENT_TYPE_AGGREGATE`, `CanonicalEvent`, `TimelineEntry`, `partitionKey()`. |
| `evidence.ts` | `EvidenceType`, `EvidenceStatus`, `EvidenceSource`, `EvidenceView`, `EvidenceVersionRecord`, `EvidenceRelationship`, `EvidenceNode/Edge/Graph`, `EvidenceLineage`, `IntegrityReport`, search + upload shapes. |
| `readiness.ts` | `ReadinessBand`, `RequirementStrength`, `GapType`, `GapSeverity`, `RequirementView`, `ReadinessGap`, `ContradictionView`, `ReadinessSnapshot`, `GapFeedItem` (= `ReadinessGap`), band thresholds and weights. |
| `dispute.ts` | `DisputeReasonCode`, `DisputeStatus`, `DisputeView`, query + create shapes. |
| `case.ts` | `CaseStatus`, `CASE_STATUS_LANES`, `CaseView`, `PackageManifest(+Item)`, `CaseXRay`, command request/result shapes. |
| `policy.ts` | `RequirementSpec`, `PolicyView`, `RequirementsResponse` (the envelope both requirement routes return), `PolicyDraft`, `PolicyDecision`, `PolicyConstraints`, `PolicyQuery` (`merchantId` required). |
| `ai.ts` | `InvestigationClassification`, `RecommendedAction`, `SafetyDecision`, `Citation`, `ModelMetadata`, `InvestigationContext`, `InvestigationResult`, `SafetyVerdict`, `ShortCircuit` (+ `SHORT_CIRCUITS`, `activeShortCircuit()`), `AdmissionDecision`, `InvestigationRecord`. |
| `simulation.ts` | `ChaosType`, `SimulationStatus`, `ChaosStatus`, run/chaos/replay/scenario shapes. |
| `audit.ts` | `AuditEventView`, `AuditQuery`, `ChainVerification`. |
| `merchant.ts` | `MerchantView`, `MerchantSummary` (the Control Tower KPI payload - flat counters, mirrors `api.dto.MerchantSummaryResponse`), `MerchantQuery`. |
| `transaction.ts` | `TransactionView`, `TransactionFacts` (+ payment/order/shipment/delivery/refund/communication facts), `TransactionDetail` (nested - mirrors `api.dto.TransactionDetailResponse`), `TransactionQuery`. |
| `metrics.ts` | `FunnelMetrics`, `FunnelQuery`, `FUNNEL_STAGES`, rate helpers. |
| `ws.ts` | `WsFrameType`, per-type data shapes, the `WsFrame` discriminated union, `LiveEvent`, `ConnectionStatus`, `CONNECTION_LABEL`. |
| `index.ts` | Barrel. Import from `@/lib/types`. |

Every enum is a **string union** whose members match contract §6 character for character, each
paired with a `readonly T[]` constant for iteration (`EVIDENCE_TYPES`, `CASE_STATUSES`, …).

### 5.3 API layer (`src/lib/api`)

| File | Contents |
|---|---|
| `client.ts` | `request()`, `api.get/post/put/patch/delete`, `buildQueryString()`, `ApiRequestError` (implements `ApiError`), `isApiError()`, `newCorrelationId()`. Adds `X-Correlation-Id` to every call, `Idempotency-Key` when supplied, normalises every failure, and short-circuits into `src/mocks` when mocks are on. |
| `endpoints/merchants.ts` | `list`, `get`, `summary`, `ready`. |
| `endpoints/transactions.ts` | `list`, `get`, `timeline`, `readiness`, `recomputeReadiness`, `evidence`, `graph`. |
| `endpoints/evidence.ts` | `search`, `get`, `versions`, `lineage`, `downloadUrl`, `upload`, `verify`. |
| `endpoints/disputes.ts` | `list`, `get`, `create`. |
| `endpoints/cases.ts` | `list`, `get`, `xray`, `packageManifest`, `approve`, `reject`, `submit`. |
| `endpoints/investigations.ts` | `get`. |
| `endpoints/policies.ts` | `list`, `get`, `requirements`, `update` (new version), `requirementsForReason`. |
| `endpoints/audit.ts` | `list`, `verifyChain`. |
| `endpoints/gaps.ts` | `list`. |
| `endpoints/metrics.ts` | `funnel`. |
| `endpoints/simulation.ts` | `startRun`, `listRuns`, `getRun`, `stopRun`, `injectChaos`, `listChaos`, `replay`, `listScenarios`, `runScenario` — all against `config.simBaseUrl`. |

### 5.4 Query layer (`src/lib/query`)

| File | Contents |
|---|---|
| `keys.ts` | `queryKeys` — hierarchical, typed, rooted at `['pdei']`. `allResourceKeys()` for a full refresh. |
| `QueryProvider.tsx` | One `QueryClient` per session. 30s stale time, no window-focus refetch (the socket does that job), no retry on non-retryable `ApiError`, **no automatic mutation retry**. |
| `useInvalidateOnWsEvent.ts` | `keysForFrame(frame)` maps each WS frame type to the keys it invalidates; the hook replays the tail in arrival order and is mounted once by `AppShell`. |

### 5.5 WebSocket layer (`src/lib/ws`)

| File | Contents |
|---|---|
| `parseFrame.ts` | zod validation of the contract §8.1 envelope plus a per-type required-id check. Never throws; a bad frame is dropped, not fatal. |
| `useControlTowerSocket.ts` | Native `WebSocket`, exponential backoff with full jitter (`backoffDelay`), idle watchdog that recycles half-open sockets, clean teardown, and a mock-mode branch that drives `src/mocks/socket.ts` instead. |

### 5.6 Stores (`src/lib/store`)

| File | Contents |
|---|---|
| `uiStore.ts` | `selectedMerchantId`, `theme`, `density`, `sidebarCollapsed`, `timeZoneMode`, `filters`, `pageSize`. Persisted to `localStorage` under **`pdei-ui`** (shape is load-bearing: the pre-paint theme script parses it). Includes `rangeToFrom()` and `TIME_RANGE_LABEL`. |
| `liveStore.ts` | Connection status, reconnect attempts, last frame/heartbeat, a bounded newest-first tail (`LIVE_TAIL_LIMIT = 200`), per-type counters, and `duplicatesDropped`. De-duplicates frames by `(type, at, merchantId, primary id)`. |

### 5.7 Formatting (`src/lib/format`)

| File | Contents |
|---|---|
| `money.ts` | `currencyExponent()`, `formatMoney()`, `formatMoneyCompact()`, `formatMoneyWithCode()`, `parseMoneyInput()`, `addMoney`/`sumMoney` (throw on currency mismatch), `formatBps`, `formatRatio`. Integer split — **never** a hardcoded `/100`. |
| `date.ts` | `formatInstant/formatDate/formatTime` (UTC by default and labelled), `formatRelative`, `formatSpan`, `formatLatency`, `deadlineState()` (contract §9.4's 48h urgency), `nowIso`, `daysAgoIso`. |
| `id.ts` | `entityKind()`, `hrefForId()` (prefix → detail route), `shortenId/shortenUuid/shortenHash`, `objectKeyFilename`, `humanizeEnum`, `humanizeEventType`. |
| `score.ts` | `bandFromScore()` (the only score→band mapping), `BAND_LABEL/DESCRIPTION/TONE`, `bandColorVar`, `toneColorVar`, per-enum tone maps, `formatScore`, `formatConfidence`, `scoreFraction`, `BAND_THRESHOLDS`. |

### 5.8 Components

`src/components/ui` — shadcn primitives: `alert`, `badge`, `button`, `card`, `dialog`,
`dropdown-menu`, `input`, `label`, `progress`, `scroll-area`, `select`, `separator`, `sheet`,
`skeleton`, `sonner` (Toaster + `toast`), `table`, `tabs`, `tooltip`.

`src/components/shared`:

| Component | Use it for |
|---|---|
| `AppShell` | The chrome. Owns the single control-tower socket and mounts WS→query invalidation. |
| `AppSidebar` | Sidebar navigation over `NAV_SECTIONS`; collapsible, tooltips when collapsed. |
| `TopBar` | Merchant selector, connection indicator, refresh-all, density / timezone / theme controls. |
| `ThemeProvider` + `THEME_BOOTSTRAP_SCRIPT` | Reflects `uiStore.theme` onto `<html>`; the script does the same before first paint. |
| `MerchantSelector` | Merchant scope; auto-selects the first merchant once. |
| `PageHeader` | Standard page heading: eyebrow, title, meta badges, description, actions. |
| `DataTable` | Generic, column-config driven, sortable, server-paged, density-aware, with built-in loading/empty/error states and row links. |
| `EmptyState` / `ErrorState` / `LoadingState` | The three non-happy paths. `ErrorState` distinguishes "gateway unreachable" from "gateway said no" and always shows the correlation id. |
| `StatTile` | One KPI: label, value, optional delta chip, optional tone. |
| `ReadinessBadge` / `ReadinessMeter` | Band badge and the 0-100 meter with band threshold ticks. |
| `StatusBadge` | One variant per status enum (`evidence`, `dispute`, `case`, `severity`, `safety`, `simulation`, `chaos`) — icon + label + reserved tone. |
| `EvidenceTypeIcon` | Icon and label per `EvidenceType`. |
| `MoneyDisplay` | The only sanctioned way to render money. |
| `TimestampDisplay` | Relative after mount, absolute in the title, UTC-aware. |
| `CopyableId` | Prefixed id with copy button and a link to its detail route. |
| `ConnectionIndicator` | Live socket state with last-frame time, attempts, duplicates dropped, and a reconnect action. |
| `JsonViewer` | Collapsible read-only JSON for payloads, constraints and chaos targets. |
| `ConfirmDialog` | Gate for every human decision, with optional typed confirmation for irreversible actions. |

### 5.9 Mocks (`src/mocks`)

| File | Contents |
|---|---|
| `random.ts` | `createRng(seed)` (mulberry32) + `seededUuid`, `sequentialId`. |
| `matrix.ts` | Mirror of `DefaultPolicyMatrix`: requirements per reason code, max-age rules, automation thresholds. |
| `dataset.ts` | `buildMockDataset(seed, now)` → merchants, transactions (+facts), evidence (+versions/lineage/integrity), readiness snapshots computed with the contract §7 formula, gaps, contradictions, timelines, graphs, disputes, cases, X-Rays, package manifests, investigations, policies, a hash-chained audit log, per-merchant summaries and funnels, simulation runs, chaos injections, scenarios. |
| `router.ts` | `resolveMockRequest` — every route of contract §8.1 and §8.5, with filtering, paging, sorting and a simulated 120 ms latency. Envelopes match the gateway exactly: `PageResponse` is `{content, …, totalElements, totalPages}`, `/transactions/{id}` is nested, `/policies` is a bare array, both requirement routes return `RequirementsResponse`, and `/gaps` and `/policies` reject a missing `merchantId` with a 400 the way the controllers do. Unknown routes throw a 404-shaped `ApiRequestError`. |
| `socket.ts` | `startMockSocket` — deterministic frame feed including heartbeats, duplicates and late timestamps. |
| `index.ts` | Public entry (`resolveMockRequest`, `startMockSocket`, `mockDataset`). |

Fixture shape (seed `20260826`): 4 merchants, 66 transactions, ~400 evidence artifacts, ~146
gaps, 20 disputes, 20 cases across **all nine** case lanes, 10 investigations, 16 policies, 100
audit entries. All four readiness bands and all six evidence statuses are represented. The four
merchants span currency exponents **0 (JPY)**, **2 (INR, GBP)** and **3 (KWD)** on purpose: a
money-formatting regression is visible on the first screen.

---

## 6. Layering rules (api / query / ws)

1. **Types are the contract.** If the gateway returns a field this app has not declared, add it to
   `src/lib/types` first. Never inline an ad-hoc shape in a component.
2. **One client.** Only `src/lib/api/client.ts` calls `fetch`. Only `src/lib/api/endpoints/*`
   calls the client. Pages and components call endpoint functions.
3. **One key factory.** Every `useQuery`/`useMutation` key comes from `queryKeys`.
   Example: `useQuery({ queryKey: queryKeys.transactions.list(query), queryFn: ({ signal }) => transactionsApi.list(query, signal) })`.
4. **The socket never writes domain state.** Frames land in `liveStore` and are converted into
   invalidations by `useInvalidateOnWsEvent`. REST is always the authority. If a new frame type
   appears, add it to `types/ws.ts`, `parseFrame.ts` and `keysForFrame()` together.
5. **Mutations invalidate explicitly.** After `casesApi.approve(...)`, invalidate
   `queryKeys.cases.detail(id)` and `queryKeys.cases.xray(id)`; do not wait for a frame that may
   never arrive.
6. **Server state vs UI state.** Anything fetched belongs to TanStack Query; anything chosen by
   the operator belongs to `uiStore`. Never cache API data in Zustand.
7. **Money and time are never formatted inline.** Use `MoneyDisplay`/`formatMoney` and
   `TimestampDisplay`/`format*`. An ESLint rule warns on `/ 100` to keep this honest.

---

## 7. Design tokens

Defined once in `src/app/globals.css`; Tailwind reads them through `tailwind.config.ts`.

- **Surface/ink (shadcn tokens, HSL triplets):** `--background`, `--foreground`, `--card`,
  `--popover`, `--primary`, `--secondary`, `--muted`, `--accent`, `--destructive`, `--border`,
  `--input`, `--ring`, `--radius`, plus `--sidebar*` for the shell.
  Light plane `#f9f9f7` / surface `#fcfcfb`; dark plane `#0d0d0d` / surface `#1a1a19`.
- **Categorical chart slots (raw hex, `var(--chart-1..8)`):** blue, orange, aqua, yellow,
  magenta, green, violet, red. Assigned **in fixed order, never cycled**; a ninth series folds
  into "Other" or becomes small multiples. Dark is a *selected* set of steps for the dark
  surface, not an inverted light palette. The set was validated for lightness band, chroma floor,
  CVD separation, normal-vision separation and surface contrast in both modes (worst adjacent CVD
  ΔE 9.1 light / 8.4 dark; worst adjacent normal-vision ΔE 19.6 / 19.3). Three light-mode slots
  (aqua, yellow, magenta) sit below 3:1 on the light surface, so charts using them need visible
  direct labels or a table view.
- **Sequential ramp:** `--seq-100 … --seq-700`, one hue, light→dark. For ordinal marks start no
  lighter than `--seq-250` on light.
- **Diverging pair:** `--div-neg` / `--div-mid` / `--div-pos` (blue ↔ red with a neutral gray
  midpoint). Never a rainbow, never a hue at the midpoint.
- **Status ramp (reserved, mode-invariant):** `--status-good #0ca30c`, `--status-warning #fab219`,
  `--status-serious #ec835a`, `--status-critical #d03b3b`, `--status-neutral #898781`.
  Never reused as a series colour, and never rendered without an icon and a label.
- **Readiness bands** map onto that ramp: READY→good, NEARLY_READY→warning, AT_RISK→serious,
  NOT_READY→critical (`--band-*`, and `bandColorVar()` in code).
- **Viz chrome:** `--viz-surface`, `--viz-grid`, `--viz-axis`, `--viz-ink*` — already applied to
  Recharts elements by `globals.css`.

Charting rules for the page agents: one y-axis per chart (**never** a dual-axis chart); legend
present for ≥2 series with selective direct labels; recessive grid and axes; hover tooltip by
default; tabular figures only in columns that must align (`.tabular` / tables), proportional
figures for standalone hero numbers.

---

## 8. Inbound contracts (what this module consumes)

**REST — `api-gateway-service`, base `NEXT_PUBLIC_API_BASE_URL` (contract §8.1):**
`/health/ready`; `/merchants`, `/merchants/{id}`, `/merchants/{id}/summary`; `/transactions`,
`/transactions/{id}`, `/timeline`, `/readiness`, `/readiness/recompute`, `/evidence`, `/graph`;
`/evidence`, `/evidence/{id}`, `/versions`, `/lineage`, `/download`, `POST /evidence`,
`/evidence/{id}/verify`; `/disputes`, `/disputes/{id}`, `POST /disputes`; `/cases`,
`/cases/{id}`, `/xray`, `/package`, `/approve`, `/reject`, `/submit`;
`/investigations/{id}`; `/policies`, `/policies/{id}`, `/requirements`, `PUT /policies/{id}`,
`/requirements?reasonCode=`; `/audit`, `/audit/verify-chain`; `/gaps`; `/metrics/funnel`.

**REST — `simulator-service`, base `NEXT_PUBLIC_SIM_BASE_URL` (contract §8.5):**
`/runs`, `/runs/{id}`, `/runs/{id}/stop`, `/chaos`, `/replay`, `/scenarios`,
`/scenarios/{key}/run`.

**WebSocket — `WS /ws/control-tower?merchantId=` (contract §8.1):** frames
`READINESS_UPDATED`, `EVIDENCE_ADDED`, `DISPUTE_CREATED`, `CASE_UPDATED`, `GAP_DETECTED`,
`CHAOS_INJECTED`, `HEARTBEAT`, in the envelope `{type, at, merchantId, data}`.

No database, Kafka topic or object store is touched from the browser — ever.

### Expected response shapes

Every shape here mirrors a Java record in `api-gateway-service` or `evidence-core`. Nothing on
this list is frontend-defined any more; where this file and the gateway disagree, the gateway
wins and this file is wrong.

- **Every list route** returns `api.dto.PageResponse<T>` = `{content, page, size, totalElements,
  totalPages}`, `page` zero-based. The gateway *wraps* evidence-core's `SearchPage` rather than
  returning it, so `items` / `total` appear nowhere on the wire.
- `GET /merchants/{id}/summary` → `api.dto.MerchantSummaryResponse`: flat counters
  (`transactions`, `averageReadinessScore`, `dominantBand`, `readinessDistribution`,
  `evidenceByStatus`, `casesByStatus`, `openDisputes`, `atRiskTransactions`, `expiringEvidence`,
  `casesRequiringReview`, `blockingGaps`, `generatedAt`). **No aggregated money and no AI
  counters** — one total across mixed currencies would be a lie (contract §5), and the funnel is
  `GET /metrics/funnel`'s job.
- `GET /gaps` → `PageResponse<ReadinessGap>`: merchant-scoped (`merchantId` is required) but the
  rows carry no merchantId, score, band or disputeId. The at-risk feed reads the band from
  `GET /transactions/{id}/readiness` per visible row.
- `GET /transactions/{id}` → `api.dto.TransactionDetailResponse` = `{transaction, facts,
  readiness, evidence, evidenceCount, openGapCount}`. The row is nested, not spread.
- `GET /policies?merchantId` → a bare `PolicyView[]`, not a page; `merchantId` is required.
- `GET /requirements` and `GET /policies/{id}/requirements` → `api.dto.RequirementsResponse` =
  `{merchantId, reasonCode, policyId, policyVersionId, defaultPolicy, requirements,
  mandatoryCount}` — never a bare `RequirementSpec[]`.
- `GET /health/ready` → `api.dto.HealthResponse` = `{status, service, dependencies, degraded,
  at}`. `dependencies` is keyed by infrastructure component (`postgres`, `redis`, `kafka`,
  `objectStore`), never by service name.
- `AdmissionDecision.shortCircuit` is always present: the backend serialises `NONE` for an
  admitted case. Test `!== 'NONE'`, never truthiness.
- `GAP_DETECTED` frames carry `transactionId` and nothing else reliably: readiness-worker nests
  `gapId` inside the payload's `gaps` array and `StreamFrame.from()` never lifts it.

Still assumed: the case commands return `CaseCommandResult`; `GET /investigations/{id}` returns
`InvestigationRecord` with decoded `result` / `verdict` / `admission` objects.

---

## 9. Outbound contracts (what this module produces)

- HTTP requests carrying `X-Correlation-Id` on every call and `Idempotency-Key` on commands that
  create or signal (dispute creation, case approve/reject/submit, chaos injection, run start).
- `GET /api/health` → `{status, service, checkedAt, config:{apiBaseUrl, wsUrl, useMocks}}` for
  Docker/Compose health checks (contract §2).
- Nothing else. The console publishes no events and writes no storage other than the browser's
  own `localStorage` key `pdei-ui`.

---

## 10. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080/api/v1` | Gateway REST base (contract §15). |
| `NEXT_PUBLIC_WS_URL` | `ws://localhost:8080/ws/control-tower` | Control-tower socket (contract §15). |
| `NEXT_PUBLIC_USE_MOCKS` | `false` | `true` serves everything from `src/mocks`, REST and socket alike. |
| `NEXT_PUBLIC_SIM_BASE_URL` | derived: gateway host on port 8088 + `/sim/v1` | simulator-service base (contract §8.5); the simulator is beside the gateway, not behind it. |

`NEXT_PUBLIC_*` values are inlined at **build** time — the Dockerfile takes them as build args.
Copy `.env.local.example` to `.env.local` for local work. There are no server-side secrets in this
module, and none may be added: everything here reaches the browser.

---

## 11. Dependencies on other modules

| Module | Relationship |
|---|---|
| `api-gateway-service` | Every REST call and the WebSocket. Hard runtime dependency. |
| `simulator-service` | Simulation and chaos console only. Degrades to an error panel when absent. |
| `evidence-core` | Source of truth for the DTO shapes mirrored in `src/lib/types` and for the readiness formula mirrored in `src/mocks`. Compile-time only — no code is shared. |
| `platform-common` | Source of the enums and the money/event envelope shapes. Compile-time only. |
| `ai-reasoning-service` | Never called from the browser. AI output reaches the UI as stored `InvestigationResult` + `SafetyVerdict` through the gateway. |

---

## 12. Build and run

```bash
cd frontend
npm install
cp .env.local.example .env.local

npm run dev          # http://localhost:3000
npm run build        # production build (standalone output)
npm start            # serve the build
npm run typecheck    # tsc --noEmit
npm run lint         # eslint (next/core-web-vitals + next/typescript)
```

**With the backend down** — the whole console still works:

```bash
NEXT_PUBLIC_USE_MOCKS=true npm run dev
```

**Docker** (host port 3000, contract §2):

```bash
docker build \
  --build-arg NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 \
  --build-arg NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws/control-tower \
  -t pdei/pdei-web:dev frontend
docker run --rm -p 3000:3000 --network pdei-net pdei/pdei-web:dev
```

Verified at the time of writing: `tsc --noEmit` clean, `next lint` clean, `next build` succeeds,
`/api/health` returns 200, and all 28 mock routes resolve against the fixture dataset.

---

## 13. Routes the two page agents will add

This module intentionally ships **no page files** for the routes below. They are the next agents'
work, and everything they need already exists.

| Route | Data it should use | Shared pieces it should compose |
|---|---|---|
| `/control-tower` | `merchantsApi.summary`, `gapsApi.list`, `metricsApi.funnel`, live tail from `liveStore` | `StatTile`, `ReadinessMeter`, `ReadinessBadge`, `DataTable`, `StatusBadge`, Recharts with `--chart-*` |
| `/transactions` | `transactionsApi.list` (+ `uiStore.filters.band`, `rangeToFrom`) | `DataTable`, `ReadinessBadge`, `MoneyDisplay`, `TimestampDisplay`, `CopyableId` |
| `/transactions/[transactionId]` | `transactionsApi.get/timeline/readiness/evidence/graph`, `recomputeReadiness` | `PageHeader`, `Tabs`, `ReadinessMeter`, `EvidenceTypeIcon`, `JsonViewer`, `ConfirmDialog` |
| `/evidence` | `evidenceApi.search` | `DataTable`, `StatusBadge` (`kind="evidence"`), `EvidenceTypeIcon`, `Input` for FTS |
| `/evidence/[evidenceId]` | `evidenceApi.get/versions/lineage/verify`, `downloadUrl` | `PageHeader`, `Tabs`, `CopyableId` (sha256 via `shortenHash`), `JsonViewer` |
| `/disputes` | `disputesApi.list` | `DataTable`, `StatusBadge` (`kind="dispute"`), `MoneyDisplay`, `deadlineState()` |
| `/disputes/[disputeId]` | `disputesApi.get`, `transactionsApi.*`, `casesApi.list` | `PageHeader`, `StatTile`, `TimestampDisplay` |
| `/cases` | `casesApi.list` grouped by `CASE_STATUS_LANES` | `StatusBadge` (`kind="case"`), `Card`, `DataTable` |
| `/cases/[caseId]` | `casesApi.xray` (one payload for every tab), `approve`/`reject`/`submit` | `Tabs`, `ReadinessMeter`, `JsonViewer`, `ConfirmDialog` (typed confirmation for submit), `StatusBadge` (`kind="safety"`) |
| `/policies` | `policiesApi.list/get/requirements/update` | `DataTable`, `Badge`, `ConfirmDialog` for publishing a new version |
| `/simulation` | `simulationApi.*` | `StatusBadge` (`kind="simulation"` / `"chaos"`), `Progress`, `ConfirmDialog`, live tail |
| `/observability` | `metricsApi.funnel`, `auditApi.verifyChain`, `liveStore` counters | `StatTile`, ordinal sequential ramp for funnel stages, `DataTable` |
| `/settings` | `merchantsApi.get`, `config` | `Card`, `Separator`, `uiStore` preferences |

Conventions for those pages: `'use client'` where hooks are needed (the shell is already a client
boundary), `PageHeader` at the top of every route, `DataTable` for every list, `ErrorState` +
`LoadingState` + `EmptyState` for the three non-happy paths, and query keys **only** from
`queryKeys`.

---

## 14. Extension points

- **A new route:** add it to `src/lib/navigation.ts` (plain data — importable by both server and
  client components) and create `src/app/<route>/page.tsx`.
- **A new endpoint:** add a typed function to the matching `src/lib/api/endpoints/*.ts` and a key
  to `queryKeys`. Never call the client from a component.
- **A new WS frame type:** extend `WsFrameType`, add its data interface and union member in
  `types/ws.ts`, add its required ids to `REQUIRED_DATA_FIELDS` in `parseFrame.ts`, and add its
  invalidation set to `keysForFrame()`.
- **A new status enum:** add the union to the right `types/*.ts`, a tone map in `format/score.ts`,
  and a `StatusKind` branch in `StatusBadge`.
- **A new fixture:** extend `buildMockDataset` and add the route to `src/mocks/router.ts`. Keep it
  deterministic — draw from the seeded `Rng`, never from `Math.random`.
- **Theming:** add tokens to both `:root` and the dark block in `globals.css`, then expose them in
  `tailwind.config.ts`. Never introduce a colour that exists in only one theme.

---

## 15. Known gaps and TODOs

1. **Evidence upload is not simulated.** `POST /evidence` is multipart; `FormData` never reaches
   the mock router, so mock mode returns an explicit "not simulated" 404 rather than inventing an
   artifact. Against a real gateway the endpoint works normally.
2. **`GET /evidence/{id}/download`** is exposed as `downloadUrl()` (a string for the browser to
   follow) rather than a fetch: a cross-origin 302's `Location` is not readable by `fetch`. If the
   gateway later returns a JSON ticket instead of a redirect, switch to the `EvidenceDownloadTicket`
   type that is already declared in `types/evidence.ts`.
3. **`MerchantSummary` and `GapFeedItem` now mirror the gateway** (see §8). `MerchantSummary` is
   the flat `MerchantSummaryResponse` and carries no money and no AI counters, and a `GET /gaps`
   row is a bare `ReadinessGap`. Two consequences the UI absorbs: the Control Tower has no
   at-risk-exposure tile, and the at-risk feed spends one
   `GET /transactions/{id}/readiness` per visible row to get its band badge. A `GapFeedResponse`
   projection on the gateway (joining `readiness_snapshots` and `disputes`) would remove that
   fan-out and restore the dispute deep-link.
4. **SSE endpoints are not wired.** Contract §8.1 also defines
   `SSE /stream/events` and `SSE /stream/cases/{caseId}`; only the WebSocket is implemented.
   `config.streamUrl()` exists as the starting point.
5. **No test suite.** There is no Vitest/Playwright setup yet. The highest-value first tests are
   `formatMoney` across exponents 0/2/3, `bandFromScore` boundaries, `parseWsFrame` rejection
   cases, and `keysForFrame` coverage of every frame type.
6. **Sorting in `DataTable` is client-side over the current page.** Every list route is
   server-paged, so cross-page sorting needs a `sort` parameter on the gateway; the column config
   already carries the information needed to send one.
7. **Zustand persistence rehydrates after mount**, so the selected merchant can flash its default
   for one frame on a cold load. The theme does not, because of the pre-paint script.
8. **No auth.** The console assumes an unauthenticated local platform (contract dev posture).
   When a service token or session arrives, it belongs in `client.ts` as a single header — nowhere
   else.
9. **Mobile is functional, not designed.** The sidebar is hidden below `md` and no drawer replaces
   it yet; `Sheet` is already available for that.
10. **Fixture timestamps are anchored to module-load time**, so the dataset is deterministic in
    shape but not in absolute instants. That is deliberate: relative renderings must look alive.
    Pass an explicit `now` to `buildMockDataset(seed, now)` when byte-for-byte reproducibility
    matters (as the self-test did).

---

## 16. Pages: operational surfaces

> Authored by the operational-surfaces page agent. Covers `/control-tower`, `/transactions`,
> `/transactions/[transactionId]`, `/evidence`, `/evidence/[evidenceId]`, `/disputes` and
> `/disputes/[disputeId]` — the seven routes of contract §14 that answer "is this merchant's
> evidence ready, and what is missing?". Sections 1–15 above are the scaffold agent's and were
> not modified. The case, policy, simulation and observability routes belong to another agent.

### 16.1 Conventions these routes follow

1. **Server shell, client view.** Every route is a small server `page.tsx` that awaits `params` /
   `searchParams` and renders a `'use client'` view from `_components/`. This keeps
   `useSearchParams` out of the tree (it would force a Suspense boundary on an otherwise static
   segment) and gives the client view a plain `string` id instead of a promise. `_components` is
   an App Router *private* folder, so it never becomes a route segment.
2. **`loading.tsx` and `error.tsx` on every segment.** Each `loading.tsx` is shaped like the page
   it replaces (heading → filter bar → table, or header → panels) so nothing jumps when data
   lands. Each `error.tsx` is a four-line wrapper around the shared `RouteErrorBoundary`, which
   surfaces the `ApiError` correlation id and Next's `digest`.
3. **Merchant scope.** Every list route reads `uiStore.selectedMerchantId` and renders an explicit
   "No merchant selected" empty state rather than an empty table when it is `null`.
4. **Filters live in `uiStore.filters`.** The bag is deliberately cross-page (see §5.6), so every
   list route ships a `FilterBar` with a visible **Clear** action — the way back out of a filter
   carried in from another page.
5. **Live updates come from invalidation, never from the socket.** No page subscribes to the
   WebSocket. `AppShell` owns the one socket, `useInvalidateOnWsEvent` maps frames to keys, and
   these pages re-fetch over REST. The single component that reads the tail directly is
   `LiveEventTicker`, and it reads it for *display* only.
6. **Money and time.** Only `MoneyDisplay` / `formatMoney` and `TimestampDisplay` / `format*`.
   No page divides by 100, and no page constructs a timestamp except through `nowIso()`.

### 16.2 `/control-tower`

The Merchant Control Tower. Above the fold, five tiles answer "what do I work next": average
readiness, at-risk transactions, open disputes, cases awaiting review, evidence expiring. All
five are counts — `MerchantSummaryResponse` aggregates no money, so there is deliberately no
exposure tile (see §8). Below, the readiness distribution, the at-risk feed, the dispute queue,
the live ticker, the expiring-evidence list and the case-status queue.

| Concern | Endpoint | Query key |
|---|---|---|
| KPI tiles, distribution, case queue | `GET /merchants/{id}/summary` | `queryKeys.merchants.summary(id)` |
| At-risk feed | `GET /gaps?merchantId&page&size` | `queryKeys.gaps.list(query)` |
| Band badge per at-risk row | `GET /transactions/{id}/readiness` | `queryKeys.transactions.readiness(id)` |
| Open dispute queue | `GET /disputes?merchantId&page&size` | `queryKeys.disputes.list(query)` |
| Expiring evidence | `GET /evidence?merchantId&status=EXPIRING` | `queryKeys.evidence.list(query)` |
| Live ticker | `WS /ws/control-tower` (read from `liveStore`) | — |

Files: `page.tsx`, `loading.tsx`, `error.tsx`, and `_components/` — `PanelCard` (the titled
`<section>` every panel uses), `ReadinessDistributionChart`, `AtRiskTransactionFeed`,
`OpenDisputeQueue`, `ExpiringEvidencePanel`.

Decisions that are not obvious from the code:

- **The at-risk feed groups gaps into transactions.** `GET /gaps` returns gaps, because a gap is
  the actionable unit; an operator works a transaction. The component rolls the returned page up
  by `transactionId`, ordered worst-severity → most-gaps → most-recent, and states in its footer
  how many gaps the rollup came from so the number is never mistaken for the whole corpus.
- **The band badge costs one fetch per visible row.** A `GET /gaps` row is a bare `ReadinessGap`
  with no score, band or disputeId, so the feed runs `useQueries` over the transactions it
  actually shows (at most `limit`, default 8) against
  `queryKeys.transactions.readiness(transactionId)` — the same key the transaction detail page
  uses, so opening a row costs nothing extra. A row renders without a badge rather than with a
  guessed one. A `GapFeedResponse` projection on the gateway would remove the fan-out.
- **"Open" disputes are computed client-side.** `GET /disputes` takes one `status`, and open is
  nine of the ten statuses in contract §6, so the queue filters with `isTerminalDispute()` — the
  TS mirror of `DisputeView.isTerminal()`.
- **The expiring panel narrows `EvidenceStatus.EXPIRING` to 7 days.** The status itself is set
  against the merchant policy's `expiringSoonDays`; contract §7 penalises the last 7 days. The
  tile shows the platform's own `expiringEvidence` count and names its horizon; the panel
  filters to 7 and says so too.
- **The readiness distribution is a bar chart, not a donut.** Four *ordinal* categories in
  contract §6 order (best → worst), never re-sorted by size. Fill is the reserved band ramp
  (`bandColorVar`), bars are ≤ 24 px with a 4 px rounded data-end square at the baseline, every
  bar is direct-labelled with its count, the value axis is dropped as redundant, and there is a
  hover tooltip plus a **Show table** toggle. One axis; no second scale anywhere on the page.

### 16.3 `/transactions`

`DataTable` over `GET /transactions` with three filters and server paging.

- Endpoint: `GET /transactions?merchantId&band&from&q&page&size` → `queryKeys.transactions.list`.
- Filters: readiness band (`uiStore.filters.band`), occurred-within preset (`filters.range` →
  `rangeToFrom`), free text (`filters.search`).
- Columns: id, customer, amount (`MoneyDisplay`), status, **readiness meter**, evidence count,
  gap count, occurred, dispute link. Rows link to the detail route and are keyboard activatable
  (`DataTable` handles `tabIndex` + Enter/Space).
- Deep link: `/transactions?band=AT_RISK` (used by the Control Tower's at-risk panel). The band is
  validated against `READINESS_BANDS` in the server shell and applied once on mount.

**`from` is quantised to the top of the hour.** `rangeToFrom()` is relative to `Date.now()`, so an
un-quantised bound would produce a different query key on every render and refetch forever. Any
future relative-time filter must do the same.

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/TransactionsView.tsx`.

### 16.4 `/transactions/[transactionId]`

Header (summary + readiness meter + **Recompute readiness**), a collapsible linked-entities
panel, and five tabs.

`GET /transactions/{id}` answers with `TransactionDetailResponse`, so the view destructures
`{ transaction, facts }` off the response before touching a field, and takes the tab counts from
`detail.evidenceCount` / `detail.openGapCount`. The row is nested under `transaction`; nothing on
this page reads `transactionId`, `amount` or `status` off the envelope itself.

| Tab | Endpoint | Notes |
|---|---|---|
| Timeline | `GET /transactions/{id}/timeline` | `EventTimeline`, occurredAt vs observedAt |
| Evidence | `GET /transactions/{id}/evidence` | grid of `EvidenceCard` |
| Graph | `GET /transactions/{id}/graph` | `EvidenceGraphView`, **fetched lazily** on first open |
| Gaps | `GET /transactions/{id}/readiness` | `GapList` + `ContradictionList` |
| Readiness | `GET /transactions/{id}/readiness` | `ReadinessBreakdown` |

`POST /transactions/{id}/readiness/recompute` seeds its response straight into
`queryKeys.transactions.readiness(id)` and then invalidates the transaction detail, `gaps.all()`
and the merchant summary — explicitly, rather than waiting for a `READINESS_UPDATED` frame that
may never arrive (§6 rule 5).

**Why the timeline shows two clocks.** `TimelineEntry.at` is the business instant. The observation
instant lives beside the entity, so the page builds an `aggregateId → observedAt` map from the
evidence list and the transaction itself and hands `EventTimeline` a resolver. Entries are ordered
by `occurredAt`; anything observed more than 60 s later is labelled *observed N late*, and anything
observed before something that occurred earlier is labelled *out of order*. Contract §17 rules 9
and 10 require the platform to tolerate this — the UI has to make it visible, or a chaos run
proves nothing.

**Why the readiness tab is a breakdown, not a score.** Contract §7 is deterministic, so it is
explainable. `ReadinessBreakdown` shows the base-score ratio (mandatory at full weight,
recommended at half), then each penalty rule with the gaps that triggered it, then the clamp. The
engine's `baseScore` / `penaltyPoints` / `score` are authoritative; the itemisation is an
*attribution* of the engine's own total, and when it cannot reproduce that total the panel says so
in a warning instead of quietly showing its own arithmetic.

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/TransactionDetailView.tsx`,
`_components/TransactionFactsPanel.tsx`, `_components/ContradictionList.tsx`.

### 16.5 `/evidence`

Full-text explorer: one search box, two facet rails, one result table.

- Endpoint: `GET /evidence?merchantId&q&type&status&page&size` → `queryKeys.evidence.list`.
- `q` is sent as raw text; the gateway turns it into a Postgres `tsquery` against the columns
  added by `V10__fts.sql`. The client never builds query syntax.
- Facets are single-select toggle rails (`FacetRail`) with `aria-pressed`, **without counts** — the
  gateway exposes no aggregation endpoint and a count taken from the visible page would misstate
  the corpus. The table caption says this out loud.
- Deep links: `?q=`, `?type=`, `?status=`; unknown enum members are dropped in the server shell
  rather than forwarded into a 400.

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/EvidenceExplorerView.tsx`,
`_components/FacetRail.tsx`.

### 16.6 `/evidence/[evidenceId]`

Four panels answering "can this artifact be trusted?".

| Panel | Endpoint |
|---|---|
| Provenance (source, source event id, created/observed/expires, object key, quality) | `GET /evidence/{id}` |
| Integrity (full sha256 + copy, **Verify**) | `POST /evidence/{id}/verify` |
| Version history | `GET /evidence/{id}/versions` |
| Lineage (ancestry chain + relationships) | `GET /evidence/{id}/lineage` |
| Download | `GET /evidence/{id}/download` via `evidenceApi.downloadUrl()` |

- **Download is an `<a href>`, not a fetch.** The route answers 302 to a presigned MinIO URL and a
  cross-origin redirect's `Location` is not readable by `fetch` (§15 gap 2).
- **Verify is a command, not a query.** The result is held by the mutation and mirrored into
  `queryKeys.evidence.integrity(id)` so a remount does not lose a check the operator paid for;
  nothing ever *fetches* that key. A failed check also invalidates the artifact itself, because the
  platform can flip its status upstream.
- **Version history is framed as append-only.** Every row is a distinct object under its own
  `v{n}/` prefix with its own digest (contract §11); the panel labels the current version and marks
  the rest *Retained*, and states that nothing is overwritten or deleted. That framing is the point
  of the panel, not decoration.

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/EvidenceDetailView.tsx`,
`_components/EvidenceIntegrityPanel.tsx`, `_components/EvidenceVersionHistory.tsx`,
`_components/EvidenceLineagePanel.tsx`.

### 16.7 `/disputes` and `/disputes/[disputeId]`

List: `GET /disputes?merchantId&status&reasonCode&page&size`, filtered by status and reason code,
**sorted by deadline** — the response window is the only clock that matters. Terminal disputes show
their close date instead of a countdown.

Detail assembles four things around the dispute:

| Panel | Endpoint |
|---|---|
| Dispute facts | `GET /disputes/{id}` |
| Deadline countdown | client-side over `deadlineAt` (`DeadlineCountdown`) |
| Linked transaction + readiness | `GET /transactions/{txId}`, `GET /transactions/{txId}/readiness?reasonCode=` |
| Linked case | `GET /cases?merchantId&size=200`, matched on `disputeId` |
| Required-evidence checklist | `GET /requirements?reasonCode&merchantId` joined with the snapshot |

- The checklist joins **rules** (`RequirementSpec`: strength, `maxAgeDays`, `provenanceRequired`,
  `minQualityScore`) with **results** (`RequirementView.satisfied` and `satisfyingEvidenceIds`) on
  evidence type. The rules arrive inside a `RequirementsResponse`, so the component reads
  `data.requirements` — the route never returns a bare array. Keeping the two apart matters: a requirement can be unsatisfied because the
  artifact is missing, or because it exists and is too old for this policy version.
- Readiness is requested **with** the dispute's `reasonCode`, so the checklist reflects the profile
  that will actually be scored rather than the merchant baseline.

Files: `disputes/page.tsx`, `disputes/loading.tsx`, `disputes/error.tsx`,
`disputes/_components/DisputesView.tsx`, `disputes/[disputeId]/page.tsx`,
`disputes/[disputeId]/loading.tsx`, `disputes/[disputeId]/error.tsx`,
`disputes/[disputeId]/_components/DisputeDetailView.tsx`,
`disputes/[disputeId]/_components/RequiredEvidenceChecklist.tsx`.

### 16.8 Shared components added by these pages

All under `src/components/shared/` and exported from its barrel. Nothing in `src/lib` was changed,
no second api client exists, and no type was redefined.

| Component | Purpose | Used by |
|---|---|---|
| `RouteErrorBoundary` | The body of every route `error.tsx`: correlation id, digest, retry, way out | all 7 routes |
| `FilterBar` | The `<search>` landmark above a list, with the Clear action | transactions, evidence, disputes |
| `FilterSelect` | Labelled enum facet; `__all__` sentinel → `undefined` (Radix cannot hold `''`) | transactions, disputes |
| `SearchInput` | Debounced labelled search; Enter flushes, Escape clears | transactions, evidence |
| `DetailList` | The `<dl>` every detail panel is made of | transaction, evidence, dispute detail |
| `HashDisplay` | sha256, short or full, always copyable in full | evidence card, evidence detail, versions |
| `DeadlineCountdown` | Ticking deadline with the contract §9.4 48-hour urgency window | dispute list + detail, control tower |
| `LiveEventTicker` | `aria-live` tail of WS frames, with a duplicates-dropped counter | control tower |
| `EvidenceCard` | One artifact: type, status, version, hash prefix, provenance, expiry | transaction Evidence tab |
| `formatBytes` | Byte sizes as counts (exported from `EvidenceCard`) | evidence card, detail, versions |
| `EventTimeline` | Vertical timeline with occurredAt vs observedAt, late + out-of-order marks | transaction Timeline tab |
| `EvidenceGraphView` | Hand-rolled layered SVG node/edge diagram, no graph library | transaction Graph tab |
| `GapList` (+ `GAP_TYPE_EXPLANATION`) | Severity-ordered gaps with what each type costs | transaction Gaps tab, control tower |
| `ReadinessBreakdown` | The contract §7 formula made legible | transaction Readiness tab |

**`EvidenceGraphView` layout algorithm** (worth knowing before touching it): a three-step
Sugiyama-style pass over the *structural* edges only — (1) longest-path layer assignment, bounded
by the node count so a cycle cannot hang it; (2) barycentre ordering within each layer, two forward
sweeps and one backward, ties broken by original index; (3) fixed-pitch placement with short
columns centred against the tallest. `CONTRADICTS` edges are excluded from layering and drawn as
dashed same-layer arcs in `--status-critical`, because they are a conflict rather than structure.
Nodes are focusable (`role="link"`, Enter/Space opens the detail page via `hrefForId`), hover and
focus drive an inspector strip beneath the canvas rather than a floating tooltip (no coordinate
maths against a scaled SVG), and a **Show as table** toggle gives a non-visual equivalent of nodes
and edges. Aggregate types map to `--chart-1..8` in fixed order and are never cycled.

### 16.9 Accessibility posture of these routes

- Every filter control is a labelled `<label for>` / `id` pair; facet toggles carry `aria-pressed`;
  the filter row is a `<search>` landmark.
- Tables are keyboard-navigable (rows are `tabIndex=0` with Enter/Space), carry `aria-sort` on
  sortable headers, and every non-obvious table has a `<caption>` (visible or `sr-only`).
- The live ticker is `aria-live="polite" aria-relevant="additions"`, so new frames are announced
  without re-reading the tail.
- The distribution chart has a table equivalent behind a toggle; so does the graph.
- State is never carried by colour alone: band, status, severity and satisfaction all ship an icon
  and a text label (`ReadinessBadge`, `StatusBadge`, `SatisfactionMark`).
- Countdowns and relative timestamps render their absolute form first and switch after mount, so
  there is no hydration mismatch on a clock.

### 16.10 Known gaps and TODOs (these routes)

1. **`NEARLY_READY` and `AT_RISK` are hard to separate by hue alone.** Running the dataviz palette
   validator over the four band colours reports the adjacent pair `#fab219 ↔ #ec835a` at ΔE 13.6
   for normal vision (floor 15), and both below 3:1 against the light surface. The ramp is
   normative in `globals.css` and shared with `ReadinessBadge` / `ReadinessMeter`, so it was not
   changed. Every use of it here carries the required relief — the band **name** on the category
   axis, a direct count label on each bar, a tooltip, and a table view — so identity never rests on
   the hue. If the ramp is ever re-stepped, re-run the validator for both surfaces.
2. **No `GET /cases?disputeId=`.** The dispute detail finds its case by pulling
   `GET /cases?merchantId&size=200` and matching on `disputeId`. Correct at fixture scale and wrong
   at real scale — the gateway should expose the filter, or `DisputeView` should carry `caseId`.
3. **`GET /transactions` has no `q` in contract §8.1.** The search box sends `q` and the mock router
   honours it; the gateway must implement it or the box degrades to a no-op filter. The parameter
   is already declared in `TransactionQuery`.
4. **Facets have no counts.** No aggregation endpoint exists. A `GET /evidence/facets` returning
   `{type: {...}, status: {...}}` for the current filter set would make the explorer far more
   useful; until then the rails are honest and countless.
5. **Sorting is per-page.** Inherited from `DataTable` (§15 gap 6); every list route here says so in
   its table caption rather than pretending otherwise.
6. **The transaction Graph tab is fetched lazily but never released.** Once opened it stays cached
   for the session. Fine at fixture scale; a very large graph would be worth a `gcTime` override.
7. **The evidence explorer and the transaction list share `filters.search`.** That is the documented
   behaviour of the cross-page filter bag (§5.6), not an accident, and both routes ship a visible
   Clear action. If it proves confusing, the fix is a second field on `UiFilters` (e.g.
   `evidenceQuery`), not a local `useState` that would break the bag's contract.
8. **No `POST /evidence` upload surface.** The explorer reads only. Merchant-portal upload is
   declared in contract §8.1 and `evidenceApi.upload()` exists, but no page calls it (and mock mode
   cannot simulate multipart — §15 gap 1).
9. **Recompute is unconfirmed.** `Recompute readiness` fires straight from the button: it is
   deterministic, idempotent and mutates no financial state, so it does not go through
   `ConfirmDialog`. Actions that signal a Temporal workflow must.
10. **Tab selection is not in the URL.** The transaction detail keeps its active tab in local state,
    so a link cannot point at "the Gaps tab of TX-000042". A `?tab=` search param on the server
    shell would fix it the same way `?band=` was handled.

---

## 17. Pages: investigation and control surfaces

> Authored by the investigation-and-control page agent. Covers `/cases`, `/cases/[caseId]`,
> `/policies`, `/simulation`, `/observability` and `/settings` — the six routes of contract §14
> that answer "why did the platform decide what it decided, and does it still behave when things
> go wrong?". Sections 1–15 are the scaffold agent's and section 16 is the operational-surfaces
> agent's; neither was modified.

### 17.1 Conventions these routes follow

1. **Server shell, client view.** Every route is a thin server `page.tsx` that awaits `params` /
   `searchParams` and renders a `'use client'` view from `_components/`. The one exception is
   `/cases/[caseId]`, which needs client-side `useSearchParams` to switch tabs without a server
   round trip and therefore sits inside an explicit `<Suspense>` boundary in its `page.tsx`.
2. **Page-local components live in `_components/`.** An App Router private folder, so it never
   becomes a route segment. Anything genuinely reusable was taken from `src/components/shared`
   rather than rebuilt — see §17.8.
3. **`loading.tsx` + `error.tsx` on every segment**, each shaped like the page it stands in for
   and each naming the most likely cause (simulator down, unpublished policy edits lost, corrupt
   `pdei-ui` blob).
4. **Every human decision passes `ConfirmDialog`.** Case approve/reject/submit, chaos injection,
   replay, run start/stop, scenario run and policy publication. Submission — the only action that
   leaves the platform — additionally requires the case id to be typed.
5. **Nothing is asserted that cannot be shown.** Where the console re-derives platform logic to
   explain a decision (contract §9.3 rule checklist, contract §9.4 short-circuit reconstruction),
   the panel says so on screen and the stored server verdict is labelled authoritative.
6. **No page opens a socket.** `AppShell` owns the one connection; these pages read `liveStore`
   for display (case-card pulse, event ticker, session counters) and let
   `useInvalidateOnWsEvent` do the re-fetching.

### 17.2 `/cases` — the case queue

Status swimlanes in workflow order (`CASE_STATUS_LANES`), one column per `CaseStatus`, each lane
captioned with what that Temporal state actually means. Cards carry merchant, amount at risk,
reason code, deadline countdown, readiness band and a **waiting on you** marker when the workflow
is parked on `humanDecision`.

| Concern | Endpoint | Query key |
|---|---|---|
| Cases | `GET /cases?merchantId&size=200` | `queryKeys.cases.list(query)` |
| Money, reason code, deadline | `GET /disputes?merchantId&size=200` | `queryKeys.disputes.list(query)` |
| Readiness per transaction | `GET /transactions?merchantId&size=200` | `queryKeys.transactions.list(query)` |
| Merchant label | `GET /merchants?size=100` | `queryKeys.merchants.list(query)` |
| Live movement | `CASE_UPDATED` frames read from `liveStore` | — |

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/CaseQueueBoard.tsx`,
`_components/CaseCard.tsx`, `_components/caseQueue.ts` (pure join + lane sort + per-currency
exposure).

Decisions worth knowing:

- **`CaseView` carries no money.** Contract §8.1 returns workflow state only, so the board joins
  three merchant-scoped list calls client-side in `caseQueue.ts`. Correct at fixture scale, and
  the join is pure so it moves server-side the day `/cases` returns enriched rows.
- **Lane order is deadline-first within "waiting on a human" first.** A lane is a queue, and the
  queue order an operator wants is: what is blocked on me, then what runs out soonest.
- **Live highlight, not live data.** A `CASE_UPDATED` frame rings the card for 30 s so a moving
  case is visible; the values themselves still come from the re-fetch that
  `useInvalidateOnWsEvent` triggers.
- **Exposure is never summed across currencies.** `laneExposure()` returns a map keyed by
  currency; the tile shows the first and says how many others exist.

### 17.3 `/cases/[caseId]` — the Case X-Ray

One payload (`GET /cases/{caseId}/xray`) backs all seven tabs, so they cannot disagree about the
same case. Three side calls fill what the X-Ray does not carry.

| Concern | Endpoint | Query key |
|---|---|---|
| Every tab | `GET /cases/{caseId}/xray` | `queryKeys.cases.xray(id)` |
| Workflow id, assignment, package version | `GET /cases/{caseId}` | `queryKeys.cases.detail(id)` |
| Confidence floor + gate thresholds | `GET /policies?merchantId` | `queryKeys.policies.list({merchantId})` |
| Admission decision | `GET /investigations/{investigationId}` | `queryKeys.investigations.detail(id)` |
| Manifest (only when the X-Ray lacks one) | `GET /cases/{caseId}/package` | `queryKeys.cases.packageManifest(id)` |
| Actions | `POST /cases/{id}/approve` · `/reject` · `/submit` | invalidates the set below |

Tabs are addressable: `?tab=overview|timeline|evidence|graph|ai|gate|package`, written with
`router.replace(..., { scroll: false })`.

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/` — `CaseXRayView` (queries, tabs,
header), `OverviewTab`, `TimelineTab`, `EvidenceTab`, `GraphTab`, `AiReasoningTab`,
`SafetyGateTab`, `PackageTab`, `CaseActions`, `WorkflowStepper`, `ConfidenceMeter`, plus three
pure modules: `workflow.ts`, `aiBypass.ts`, `safetyRules.ts`.

**Overview.** Dispute facts, exposure, deadline, current `CaseStatus`, the readiness meter with
the contract §7 arithmetic (base − penalties = score) spelled out, open gaps, contradictions, and
`WorkflowStepper`.

**`WorkflowStepper` + `workflow.ts`.** The twelve steps of `DisputeCaseWorkflow` verbatim from
contract §10, each with its kind (activity / signal / timer) and what it does.
`STATUS_TO_ORDINAL` maps `CaseStatus` onto the step the case is sitting on; step 6 `investigate`
renders as **skipped** whenever admission control short-circuited, which is the point — most
cases never reach the model. `FAILED` deliberately renders as *unknown* positions rather than
inventing progress, and points at the Temporal UI.

**Timeline.** The shared `EventTimeline` (two clocks, late/out-of-order call-outs) with a
case-specific lens on top: Everything / Commerce / Evidence / Dispute & case / Customer contact.
`observedAtFor()` reads `details.observedAt` when the gateway supplies it and shows nothing when
it does not.

**Evidence.** Every attached artifact judged against the requirement set the readiness snapshot
used — the requirement column comes *first*, because the question is not "what do we have" but
"does what we have satisfy what it was supposed to satisfy". `linkFor()` resolves an artifact to
`satisfies` / `matches-unsatisfied` / `prohibited` / `not-required`, preferring the snapshot's own
`satisfyingEvidenceIds` over a type match. Unsatisfied requirements get their own panel;
prohibited types get a §9.3-rule-6 warning.

**Graph.** The shared `EvidenceGraphView` with `highlightedIds` = cited evidence ∪ package
contents. The sidebar separates the two ("cited" vs "in package"), because an artifact a model
mentioned and an artifact the platform actually filed are different claims.

**AI reasoning.** The honest rendering. Classification with what it means, the calibrated
`ConfidenceMeter`, the reasoning summary and draft narrative, the admission-control breakdown
(the four contract §9.4 terms with their weights), provider/model/token/latency metadata, and —
the part that matters — **every citation as a claim bound to its evidence id**. A citation whose
id is not attached to this case, or that the stored verdict lists in `unsupportedClaims`, is
flagged critical and named as such. Dangling `supportingEvidence` ids get their own alert. When
`xray.investigation` is null the tab says so in the largest type on the page and shows the
short-circuit instead.

**Safety gate.** The product's argument, laid out left-to-right: what the model proposed → the
gate → what the platform decided. Then the stored `SafetyVerdict` (decision, recorded reasons,
unsupported claims), then all seven contract §9.3 rules with pass / fail / not-applicable and the
value each was applied to, then the four non-negotiable rules from contract §17 in plain words.

**Package.** The manifest as a ledger: bundle key, full sha256, size, version, policy version,
readiness at assembly, the filed narrative, and every file with its path inside the zip, its
version and its hash. `Download manifest.json` builds a Blob client-side; individual files go
through `GET /evidence/{id}/download`.

**Actions (`CaseActions`).** `useMutation` per command with an idempotency key from
`newCorrelationId()`. Optimistic: `onMutate` cancels and patches `cases.detail` + `cases.xray`
with the expected status, `onError` rolls back, `onSuccess` replaces the guess with the workflow's
real answer from `CaseCommandResult`, and `onSettled` invalidates `cases.detail`, `cases.xray`,
`cases.packageManifest`, `cases.all`, `disputes.all`, `investigations.all`, `metrics.all`,
`audit.all` and `merchants.summary` — the same set a `CASE_UPDATED` frame would, so the screen is
right even if no frame arrives. Approve/Reject enable only at `AWAITING_APPROVAL`; Submit only at
`PREPARED` with a manifest present, and requires the case id typed.

### 17.4 `/policies`

Three surfaces over one immutable resource.

| Concern | Endpoint | Query key |
|---|---|---|
| Matrix, thresholds, history | `GET /policies?merchantId` (a bare array, not a page) | `queryKeys.policies.list({merchantId})` |
| Publish | `PUT /policies/{policyId}` | invalidates `policies.all`, `transactions.all`, `gaps.all` |

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/PolicyConsole.tsx`,
`RequirementMatrix.tsx`, `ThresholdsPanel.tsx`, `VersionHistory.tsx`, `policyDraft.ts`.

- **Matrix:** rows are the merchant's policies (one per reason code plus the baseline), columns
  are all thirteen `EvidenceType`s, cells are `MANDATORY / RECOMMENDED / OPTIONAL / PROHIBITED /
  —`. Cells are **native `<select>`s** on purpose: at 13 × n controls a native select is the only
  one that stays keyboard-navigable, screen-reader correct and cheap. The letter carries the
  value; the colour repeats it.
- **Drafts, never edits.** `policyDraft.ts` is pure: `toDraft`, `cellValue`, `setCell`,
  `isDirty` (order-insensitive canonical compare), `describeChanges`. Setting a cell resets its
  weight to the strength default (3/2/1/0) and keeps `prohibitedEvidenceTypes` in step with the
  matrix. A draft is re-seeded automatically when the server returns a newer `policyVersionId`.
- **Thresholds:** `autoPrepareMinConfidence` and `minReadinessScoreForAutoPrepare` as sliders,
  `maxContradictions` / `responseWindowDays` / `expiringSoonDays` as integers,
  `humanReviewAboveAmountMinor` entered in **major** units and stored in minor units through
  `parseMoneyInput()` — no float ever touches it. Each field names the contract rule it feeds.
- **Publishing** is sequential, one `PUT` per dirty policy, behind a `ConfirmDialog` that lists
  the field-level diff per policy. Versions published this session are appended to the history.

### 17.5 `/simulation`

| Concern | Endpoint | Query key |
|---|---|---|
| Launch / list / progress / stop | `POST /sim/v1/runs`, `GET /runs`, `GET /runs/{id}`, `POST /runs/{id}/stop` | `queryKeys.simulation.runs()` / `.run(id)` |
| Scenarios | `GET /sim/v1/scenarios`, `POST /scenarios/{key}/run` | `queryKeys.simulation.scenarios()` |
| Chaos | `POST /sim/v1/chaos`, `GET /sim/v1/chaos` | `queryKeys.simulation.chaos()` |
| Replay | `POST /sim/v1/replay` | — |
| Chaos targets | `GET /transactions?merchantId`, `GET /transactions/{id}/evidence` | existing transaction keys |

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/SimulationConsole.tsx`,
`RunLauncher.tsx`, `RunProgressPanel.tsx`, `ScenarioLibrary.tsx`, `ChaosPanel.tsx`,
`InjectionHistory.tsx`, `ReplayPanel.tsx`, `EventTicker.tsx`, `chaosCatalog.ts`.

- **`chaosCatalog.ts`** has one `ChaosSpec` per `ChaosType` in contract §6 — all thirteen — with
  what it does, **what surviving it proves**, which target selector it needs (`transactionId`,
  `evidenceId`, `service`, `topic`) and whether it takes `delayMs` / `count`. It also holds the
  service list, the eight Kafka topic names (mirroring `common.kafka.Topics`) and the failure
  profiles.
- **One target, thirteen controls.** The target (transaction → its evidence, service, topic,
  delay, count) is chosen once at the top and reused by every card, because the interesting
  question is what the platform does to *this* transaction under each failure. Every injection
  shows its exact request body in the confirmation.
- **Progress polls only while alive.** `RunProgressPanel` polls `GET /runs/{id}` every 2 s while
  `PENDING`/`RUNNING` and stops dead at a terminal status.
- **Scenario cards state the expected outcome before the run.** That ordering is the value: a
  demo that predicts and then shows is evidence.
- **The ripple is visible.** `EventTicker` wraps the shared `LiveEventTicker` with the two numbers
  that make a chaos run legible — frames per type, and **duplicates dropped**. Under
  `DUPLICATE_EVENT` or a replay, that second counter climbing while nothing else moves *is* the
  idempotency demonstration. The panel flashes on every accepted injection, run start or replay.
- **Run launcher** takes the dispute rate as a percentage and multiplies by `0.01` to reach the
  `[0,1]` rate the API wants; the seed has a randomise button and is described as the
  reproducibility handle it is (contract §17 rule 11).

### 17.6 `/observability`

| Concern | Endpoint | Query key |
|---|---|---|
| Funnel | `GET /metrics/funnel?merchantId&from` | `queryKeys.metrics.funnel(query)` |
| Platform health | `GET /health/ready` (30 s refetch) | `queryKeys.health.ready()` |
| Audit integrity | `GET /audit/verify-chain?merchantId` | `queryKeys.audit.chain(id)` |
| Session counters | `liveStore` | — |

Files: `page.tsx`, `loading.tsx`, `error.tsx`, `_components/ObservabilityView.tsx`,
`FunnelChart.tsx`, `ServiceHealthGrid.tsx`, `services.ts`.

- **`FunnelChart`** is a real funnel: hand-drawn SVG, one row per `FUNNEL_STAGES` entry, width
  proportional to the top stage, sloped connectors between rows. Colour is a **single-hue
  sequential ramp** interpolated with `color-mix` between `--seq-250` and `--seq-700` (ordinal
  data, never categorical slots, never a rainbow). Every row carries its absolute count, its
  share of the previous stage and its share of events, so nothing depends on comparing areas.
- **The reduction is the headline.** `1 − aiAdmissionRate(m)` is a KPI tile *and* a callout under
  the chart explaining what admission control (contract §9.4) actually turned away and why that
  costs nothing.
- **`ServiceHealthGrid` reports only what a browser can honestly know.** Three real signals: the
  gateway's own `HealthResponse.status`, the four dependencies it probes for itself
  (`postgres`, `redis`, `kafka`, `objectStore`, rendered as their own row of cards), and the
  socket state. `/health/ready` is keyed by infrastructure component and not by service name, so
  every other contract §2 service is listed with its port and a link to its actuator and marked
  **not probed** — a cross-origin actuator is unreachable from the page, and drawing it green on
  no evidence would be a lie.
- **Consoles:** Grafana `localhost:3001` and Temporal UI `localhost:8233` as the two primary
  cards (contract §14 asks for exactly these), with Prometheus, Kafka UI and the MinIO console as
  secondary chips. `error.tsx` repeats the two primary links, because a broken page is exactly
  when you want them.

### 17.7 `/settings`

Two kinds of thing, deliberately not mixed:

- **This browser's preferences** — merchant scope, theme, table density, UTC/local timestamps,
  page size, reset filters — all editable, all in `uiStore`, all persisted under `pdei-ui`.
- **This deployment's configuration** — `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_WS_URL`,
  `NEXT_PUBLIC_SIM_BASE_URL`, `NEXT_PUBLIC_USE_MOCKS` — reported as facts with the variable that
  controls them and a copy button, never as a control that silently does nothing. Mock mode shows
  its current state and the two commands that change it, because the flag is inlined at build
  time and a fake toggle would be worse than none.
- **AI provider** states the contract §9.5 abstraction, says plainly that `PDEI_AI_PROVIDER` is
  server-side and never shipped to the browser, and links to `GET /v1/providers` on `:8000` for
  the ground truth. It does *not* show admitted / denied / auto-prepared counts:
  `MerchantSummaryResponse` carries none, and those numbers belong to `GET /metrics/funnel`, so
  the section points at `/observability` instead of inventing them.

Endpoints: `GET /merchants?size=100`, `GET /merchants/{id}`.

### 17.8 Shared components these routes consume (and the ones they did not add)

Reused as-is: `PageHeader`, `DataTable`, `StatTile`, `StatusBadge`, `ReadinessBadge`,
`ReadinessMeter`, `MoneyDisplay`, `TimestampDisplay`, `CopyableId`, `HashDisplay`,
`DeadlineCountdown`, `EvidenceTypeIcon`, `formatBytes`, `JsonViewer`, `ConfirmDialog`,
`ConnectionIndicator`, `EmptyState` / `ErrorState` / `LoadingState`, `EventTimeline`,
`EvidenceGraphView`, `LiveEventTicker`.

Nothing was added to `src/components/shared` by this agent. Everything new is page-local under
`_components/` because it is genuinely single-route: a case-workflow stepper, a confidence meter
calibrated against a policy floor, a requirement-matrix grid, a chaos catalogue, a funnel chart.
If a second route ever needs one of them, `ConfidenceMeter` and `FunnelChart` are the two that
would move first.

### 17.9 Pure modules worth reading before changing anything

| File | What it is | Why it is pure |
|---|---|---|
| `app/cases/_components/caseQueue.ts` | case + dispute + transaction join, lane sort, exposure | so the board is presentational and the join can move server-side |
| `app/cases/[caseId]/_components/workflow.ts` | the twelve contract §10 steps, `CaseStatus` → step | a presentation of the workflow, not a second implementation |
| `app/cases/[caseId]/_components/aiBypass.ts` | reconstructs the contract §9.4 short-circuit | explains only; the stored `AdmissionDecision` always wins |
| `app/cases/[caseId]/_components/safetyRules.ts` | re-evaluates the seven contract §9.3 rules | shows the working; the stored `SafetyVerdict` is authoritative |
| `app/policies/_components/policyDraft.ts` | `PolicyView` → `PolicyDraft`, cell edits, diffing | policy versions are immutable; drafts never mutate one |
| `app/simulation/_components/chaosCatalog.ts` | one `ChaosSpec` per contract §6 `ChaosType` | the console's contract with simulator-service about target shapes |
| `app/observability/_components/services.ts` | contract §2 registry, consoles, headline metrics | addresses and copy, no behaviour |

### 17.10 Known gaps and TODOs (these routes)

1. **`GET /cases` returns no money, reason code, deadline or readiness.** `/cases` joins three
   list calls to build a card. The gateway should either enrich `CaseView` or accept that the
   board is O(3) calls per merchant and capped at 200 rows per resource.
2. **Readiness on a case card is the transaction's *latest* snapshot**, not the one captured when
   the dispute landed. The at-dispute snapshot exists only on the X-Ray. The board says so under
   the lanes rather than quietly showing the wrong number.
3. **`CaseXRay` carries no `AdmissionDecision`.** When `investigation` is null the console has no
   record of why the model was skipped, so `aiBypass.ts` reconstructs it from readiness,
   contradictions, evidence count and the deadline — and the panel is labelled *reconstructed*.
   Adding `admission: AdmissionDecision | null` to `CaseXRay` would delete that whole module.
4. **The §9.3 checklist is a re-evaluation.** `safetyRules.ts` re-runs the seven rules client-side
   to show which value tripped which rule; rules 3–6 need the applicable policy and report *Not
   evaluated* without one. The gate's stored `reasons` remain the authority and are shown above
   the checklist. A per-rule outcome array on `SafetyVerdict` would make this exact.
5. **The applicable policy is guessed by reason code.** The console picks
   `policies.find(reasonCode === xray.reasonCode) ?? defaultPolicy`. The case was gated against a
   specific `policyVersionId`, which the readiness snapshot knows but the policy list cannot be
   queried by. A `GET /policies/version/{policyVersionId}` route would fix both this and the
   history gap below.
6. **No bundle download.** Contract §8.1 has `GET /cases/{id}/package` for the manifest but no
   route for the zip. The Package tab downloads a client-built `manifest.json`, links each file
   through `GET /evidence/{id}/download`, and shows the MinIO object key instead of a dead button.
7. **No policy version history route.** `GET /policies/{policyId}/versions` does not exist, so the
   history table shows the effective interval of each policy plus whatever this session published.
   The full immutable chain is only visible in `pdei.policy_versions` and the audit log.
8. **No `POST /policies`.** A reason code with no policy row renders as a disabled matrix row; the
   console can version an existing policy but cannot create one.
9. **`failureProfile` is a frontend vocabulary.** Contract §8.5 takes a free string and publishes
   no enumeration, so `FAILURE_PROFILES` in `chaosCatalog.ts` is the console's own list. Replace
   it the day the simulator exposes its profiles.
10. **Chaos target shapes are a convention, not a contract.** `ChaosRequest.target` is
    `Record<string, unknown>`; `chaosCatalog.ts` decides that `DELETE_EVIDENCE` sends
    `{evidenceId, transactionId}` and `KILL_WORKER` sends `{service, consumerGroup}`. Simulator
    and console must be changed together.
11. **Service health is one endpoint deep.** Only `/health/ready` (its `status` plus the four
    infrastructure `dependencies` the gateway probes) and the socket are real signals; every
    sibling service is marked *not probed*. Per-service entries in `ReadinessProbeService` would
    make the service half of the grid meaningful.
12. **The actor on a human decision is hard-coded** to `console-operator` (`CaseActions`), because
    there is no auth (§15 gap 8). When a session exists, take the actor from it there — it is one
    constant.
13. **Optimistic status is a guess.** `OPTIMISTIC_STATUS` assumes approve → `PREPARED`, reject →
    `AWAITING_EVIDENCE`, submit → `SUBMITTED`. The server's `CaseCommandResult.status` replaces it
    immediately, and a failure rolls back, but a workflow that routes differently will flicker.
14. **Unpublished policy edits are component state.** Navigating away or crashing loses them;
    `error.tsx` says so. Persisting drafts would need a store, and a persisted draft of a
    financial policy is a liability, so this is deliberate.
15. **The funnel has no time series.** `GET /metrics/funnel` returns one window, so the page shows
    a snapshot and its ratios. Trend lines need a windowed endpoint or a Prometheus query proxy —
    which is what the Grafana link is for.
16. **No tests.** Same as §15 gap 5. The highest-value first tests here are `stepStateFor` across
    all nine statuses, `evaluateGateChecklist` for each of the seven rules, `deriveBypass` for the
    three short-circuits, `setCell`/`isDirty` round-trips, and that `CHAOS_CATALOG` covers exactly
    `CHAOS_TYPES`.
