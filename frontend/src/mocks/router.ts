/**
 * Mock request router. `lib/api/client.ts` calls `resolveMockRequest` instead of `fetch`
 * whenever NEXT_PUBLIC_USE_MOCKS is true, so every endpoint module and every page stays
 * completely unaware that the backend is absent.
 *
 * Routes here mirror contract 8.1 (gateway) and 8.5 (simulator) exactly. An unmatched path
 * throws the same ApiRequestError shape a 404 from the gateway would produce - a missing mock
 * must look like a missing route, not like a silent empty page.
 */
import { ApiRequestError, type HttpMethod, type QueryParams } from '@/lib/api/client';
import type { PageResponse } from '@/lib/types/common';
import type { EvidenceStatus, EvidenceType } from '@/lib/types/evidence';
import type { ReadinessBand } from '@/lib/types/readiness';
import type { GapSeverity, GapType } from '@/lib/types/readiness';
import type { DisputeReasonCode, DisputeStatus } from '@/lib/types/dispute';
import type { CaseStatus } from '@/lib/types/case';
import type { PolicyView, RequirementsResponse } from '@/lib/types/policy';
import type { ChaosType, SimulationRun } from '@/lib/types/simulation';
import { mockDataset } from './dataset';

export interface MockRequest {
  method: HttpMethod;
  path: string;
  query?: QueryParams;
  body?: unknown;
  url: string;
  correlationId: string;
}

/** Simulated round-trip, so loading states are exercised rather than skipped. */
const LATENCY_MS = 120;

function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), LATENCY_MS));
}

function notFound(request: MockRequest, detail: string): ApiRequestError {
  return new ApiRequestError({
    status: 404,
    code: 'NOT_FOUND',
    message: detail,
    correlationId: request.correlationId,
    url: request.url,
    method: request.method,
    details: { mock: true },
  });
}

function badRequest(request: MockRequest, detail: string): ApiRequestError {
  return new ApiRequestError({
    status: 400,
    code: 'VALIDATION_FAILED',
    message: detail,
    correlationId: request.correlationId,
    url: request.url,
    method: request.method,
    details: { mock: true },
  });
}

/**
 * `api.dto.RequirementsResponse`: both requirement routes answer with this envelope, never a
 * bare `RequirementSpec[]`.
 */
function requirementsResponse(
  policy: PolicyView | null,
  merchantId: string | null = null,
  reasonCode: DisputeReasonCode | null = null,
): RequirementsResponse {
  const requirements = policy?.requirements ?? [];
  return {
    merchantId: policy?.merchantId ?? merchantId,
    reasonCode: policy?.reasonCode ?? reasonCode,
    policyId: policy?.policyId ?? null,
    policyVersionId: policy?.policyVersionId ?? null,
    defaultPolicy: policy?.defaultPolicy ?? true,
    requirements,
    mandatoryCount: requirements.filter((item) => item.strength === 'MANDATORY').length,
  };
}

function str(query: QueryParams | undefined, key: string): string | undefined {
  const value = query?.[key];
  if (value === undefined || value === null || value === '') return undefined;
  return String(value);
}

function num(query: QueryParams | undefined, key: string, fallback: number): number {
  const value = str(query, key);
  const parsed = value === undefined ? Number.NaN : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function paginate<T>(items: readonly T[], query: QueryParams | undefined): PageResponse<T> {
  const page = Math.max(0, num(query, 'page', 0));
  const size = Math.min(200, Math.max(1, num(query, 'size', 25)));
  const start = page * size;
  const totalElements = items.length;
  return {
    content: items.slice(start, start + size),
    page,
    size,
    totalElements,
    totalPages: Math.ceil(totalElements / size),
  };
}

/** Splits `/transactions/TX-1/timeline` into `['transactions','TX-1','timeline']`. */
function segments(path: string): string[] {
  return path.split('?')[0]!.split('/').filter(Boolean);
}

/* ------------------------------------------------------------------ gateway routes */

function resolveGateway(request: MockRequest): unknown {
  const parts = segments(request.path);
  const [head, second, third, fourth] = parts;
  const { method, query } = request;
  const data = mockDataset;

  if (head === 'health' && second === 'ready') {
    // api.dto.HealthResponse: dependencies are keyed by infrastructure component, not by service.
    return {
      status: 'READY',
      service: 'api-gateway-service',
      dependencies: { postgres: 'UP', redis: 'UP', kafka: 'UP', objectStore: 'UP' },
      degraded: [],
      at: new Date().toISOString(),
    };
  }

  if (head === 'merchants') {
    if (!second) return paginate(data.merchants, query);
    const merchant = data.merchants.find((item) => item.merchantId === second);
    if (!merchant) throw notFound(request, `merchant ${second} not found`);
    if (third === 'summary') {
      const summary = data.summaries[second];
      if (!summary) throw notFound(request, `summary for ${second} not found`);
      return summary;
    }
    return merchant;
  }

  if (head === 'transactions') {
    if (!second) {
      const merchantId = str(query, 'merchantId');
      const band = str(query, 'band') as ReadinessBand | undefined;
      const from = str(query, 'from');
      const to = str(query, 'to');
      const text = str(query, 'q')?.toLowerCase();
      const filtered = data.transactions.filter((item) => {
        if (merchantId && item.merchantId !== merchantId) return false;
        if (band && item.readinessBand !== band) return false;
        if (from && item.occurredAt < from) return false;
        if (to && item.occurredAt > to) return false;
        if (
          text &&
          !`${item.transactionId} ${item.customerId ?? ''} ${item.externalRef ?? ''}`
            .toLowerCase()
            .includes(text)
        ) {
          return false;
        }
        return true;
      });
      return paginate(
        [...filtered].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)),
        query,
      );
    }

    const detail = data.transactionsById[second];
    if (!detail) throw notFound(request, `transaction ${second} not found`);

    // TransactionDetailResponse: the row nested under `transaction`, plus facts and counters.
    if (!third) return detail;
    if (third === 'timeline') return data.timelineByTransaction[second] ?? [];
    if (third === 'evidence') return data.evidenceByTransaction[second] ?? [];
    if (third === 'graph') return data.graphByTransaction[second] ?? null;
    if (third === 'readiness') {
      const snapshot = data.readinessByTransaction[second];
      if (!snapshot) throw notFound(request, `readiness for ${second} not found`);
      if (fourth === 'recompute' && method === 'POST') {
        // Recomputation is deterministic: the same evidence yields the same score. Only the
        // computedAt stamp moves, which is exactly what the real engine does.
        const recomputed = { ...snapshot, computedAt: new Date().toISOString() };
        data.readinessByTransaction[second] = recomputed;
        return recomputed;
      }
      return snapshot;
    }
  }

  if (head === 'evidence') {
    if (!second && method === 'POST') {
      // The real route is a multipart upload; FormData never reaches the mock router, so this
      // is an honest failure rather than a fabricated artifact.
      throw notFound(request, 'multipart evidence upload is not simulated in mock mode');
    }
    if (!second) {
      const merchantId = str(query, 'merchantId');
      const type = str(query, 'type') as EvidenceType | undefined;
      const status = str(query, 'status') as EvidenceStatus | undefined;
      const transactionId = str(query, 'transactionId');
      const text = str(query, 'q')?.toLowerCase();
      const filtered = Object.values(data.evidenceById).filter((item) => {
        if (merchantId && item.merchantId !== merchantId) return false;
        if (type && item.type !== type) return false;
        if (status && item.status !== status) return false;
        if (transactionId && item.transactionId !== transactionId) return false;
        if (
          text &&
          !`${item.evidenceId} ${item.filename} ${item.summary ?? ''} ${item.type}`
            .toLowerCase()
            .includes(text)
        ) {
          return false;
        }
        return true;
      });
      return paginate(
        [...filtered].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
        query,
      );
    }

    const evidence = data.evidenceById[second];
    if (!evidence) throw notFound(request, `evidence ${second} not found`);
    if (!third) return evidence;
    if (third === 'versions') return data.versionsByEvidence[second] ?? [];
    if (third === 'lineage') return data.lineageByEvidence[second] ?? null;
    if (third === 'verify' && method === 'POST') {
      return data.integrityByEvidence[second] ?? null;
    }
    if (third === 'download') {
      return { evidenceId: second, url: `about:blank#${evidence.objectKey}`, expiresAt: new Date(Date.now() + 900_000).toISOString() };
    }
  }

  if (head === 'disputes') {
    if (!second && method === 'POST') {
      const body = (request.body ?? {}) as Record<string, unknown>;
      const transactionId = String(body['transactionId'] ?? '');
      const detail = data.transactionsById[transactionId];
      if (!detail) throw notFound(request, `transaction ${transactionId} not found`);
      const transaction = detail.transaction;
      const disputeId = `DSP-${String(data.disputes.length + 1).padStart(5, '0')}`;
      const openedAt = String(body['openedAt'] ?? new Date().toISOString());
      const created = {
        disputeId,
        merchantId: String(body['merchantId'] ?? transaction.merchantId),
        transactionId,
        reasonCode: (body['reasonCode'] as DisputeReasonCode) ?? 'GOODS_NOT_RECEIVED',
        status: 'OPEN' as DisputeStatus,
        amount: (body['amount'] as typeof transaction.amount) ?? transaction.amount,
        networkCaseRef: (body['networkCaseRef'] as string) ?? null,
        source: (body['source'] as string) ?? 'MERCHANT_PORTAL',
        openedAt,
        deadlineAt:
          (body['deadlineAt'] as string) ??
          new Date(new Date(openedAt).getTime() + 21 * 86_400_000).toISOString(),
        closedAt: null,
        updatedAt: openedAt,
      };
      data.disputes.unshift(created);
      data.disputesById[disputeId] = created;
      transaction.disputeId = disputeId;
      return created;
    }
    if (!second) {
      const merchantId = str(query, 'merchantId');
      const status = str(query, 'status') as DisputeStatus | undefined;
      const reasonCode = str(query, 'reasonCode') as DisputeReasonCode | undefined;
      const filtered = data.disputes.filter((item) => {
        if (merchantId && item.merchantId !== merchantId) return false;
        if (status && item.status !== status) return false;
        if (reasonCode && item.reasonCode !== reasonCode) return false;
        return true;
      });
      return paginate(
        [...filtered].sort((a, b) => b.openedAt.localeCompare(a.openedAt)),
        query,
      );
    }
    const dispute = data.disputesById[second];
    if (!dispute) throw notFound(request, `dispute ${second} not found`);
    return dispute;
  }

  if (head === 'cases') {
    if (!second) {
      const merchantId = str(query, 'merchantId');
      const status = str(query, 'status') as CaseStatus | undefined;
      const filtered = data.cases.filter((item) => {
        if (merchantId && item.merchantId !== merchantId) return false;
        if (status && item.status !== status) return false;
        return true;
      });
      return paginate(
        [...filtered].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
        query,
      );
    }

    const caseView = data.casesById[second];
    if (!caseView) throw notFound(request, `case ${second} not found`);
    if (!third) return caseView;
    if (third === 'xray') return data.xrayByCase[second] ?? null;
    if (third === 'package') {
      const manifest = data.packageByCase[second];
      if (!manifest) throw notFound(request, `case ${second} has no package yet`);
      return manifest;
    }
    if (method === 'POST' && (third === 'approve' || third === 'reject' || third === 'submit')) {
      const nextStatus: CaseStatus =
        third === 'approve' ? 'PREPARED' : third === 'reject' ? 'AWAITING_EVIDENCE' : 'SUBMITTED';
      const updated = { ...caseView, status: nextStatus, updatedAt: new Date().toISOString() };
      data.casesById[second] = updated;
      const index = data.cases.findIndex((item) => item.caseId === second);
      if (index >= 0) data.cases[index] = updated;
      const xray = data.xrayByCase[second];
      if (xray) xray.caseStatus = nextStatus;
      return {
        caseId: second,
        status: nextStatus,
        workflowId: caseView.workflowId,
        signal: third === 'submit' ? 'submitRepresentment' : 'humanDecision',
        acceptedAt: new Date().toISOString(),
      };
    }
  }

  if (head === 'investigations' && second) {
    const investigation = data.investigationsById[second];
    if (!investigation) throw notFound(request, `investigation ${second} not found`);
    return investigation;
  }

  if (head === 'policies') {
    if (!second) {
      // PolicyController.list: a bare array, and merchantId is required.
      const merchantId = str(query, 'merchantId');
      if (!merchantId) throw badRequest(request, 'merchantId is required on GET /policies');
      const reasonCode = str(query, 'reasonCode') as DisputeReasonCode | undefined;
      return data.policies.filter((item) => {
        if (item.merchantId !== merchantId) return false;
        if (reasonCode && item.reasonCode !== reasonCode) return false;
        return true;
      });
    }
    const policy = data.policiesById[second];
    if (!policy) throw notFound(request, `policy ${second} not found`);
    if (third === 'requirements') return requirementsResponse(policy);
    if (method === 'PUT') {
      // A new version, never an edit: the id changes and the previous interval closes.
      const next = {
        ...policy,
        version: policy.version + 1,
        policyVersionId: `${policy.policyId.replace('POL-', 'POLV-')}-${policy.version + 1}`,
        effectiveFrom: new Date().toISOString(),
      };
      data.policiesById[second] = next;
      const index = data.policies.findIndex((item) => item.policyId === second);
      if (index >= 0) data.policies[index] = next;
      return next;
    }
    return policy;
  }

  if (head === 'requirements') {
    const reasonCode = str(query, 'reasonCode') as DisputeReasonCode | undefined;
    const merchantId = str(query, 'merchantId');
    const policy =
      data.policies.find(
        (item) =>
          item.reasonCode === (reasonCode ?? null) &&
          (merchantId === undefined || item.merchantId === merchantId),
      ) ?? data.policies.find((item) => item.reasonCode === (reasonCode ?? null));
    return requirementsResponse(policy ?? null, merchantId ?? null, reasonCode ?? null);
  }

  if (head === 'gaps') {
    // GapController declares merchantId as required and returns PageResponse<ReadinessGap>.
    const merchantId = str(query, 'merchantId');
    if (!merchantId) throw badRequest(request, 'merchantId is required on GET /gaps');
    const type = str(query, 'type') as GapType | undefined;
    const severity = str(query, 'severity') as GapSeverity | undefined;
    const filtered = (data.gapsByMerchant[merchantId] ?? []).filter((item) => {
      if (type && item.type !== type) return false;
      if (severity && item.severity !== severity) return false;
      return true;
    });
    const rank: Record<GapSeverity, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
    return paginate(
      [...filtered].sort(
        (a, b) => rank[a.severity] - rank[b.severity] || b.detectedAt.localeCompare(a.detectedAt),
      ),
      query,
    );
  }

  if (head === 'audit') {
    if (second === 'verify-chain') {
      const merchantId = str(query, 'merchantId') ?? '';
      const checked = data.auditEvents.filter((item) => item.merchantId === merchantId).length;
      return {
        merchantId,
        intact: true,
        eventsChecked: checked,
        firstDivergenceId: null,
        detail: null,
        verifiedAt: new Date().toISOString(),
      };
    }
    const entityId = str(query, 'entityId');
    const entityType = str(query, 'entityType');
    const merchantId = str(query, 'merchantId');
    const actor = str(query, 'actor');
    const filtered = data.auditEvents.filter((item) => {
      if (entityId && item.entityId !== entityId) return false;
      if (entityType && item.entityType !== entityType) return false;
      if (merchantId && item.merchantId !== merchantId) return false;
      if (actor && item.actor !== actor) return false;
      return true;
    });
    return paginate(
      [...filtered].sort((a, b) => b.sequenceNo - a.sequenceNo),
      query,
    );
  }

  if (head === 'metrics' && second === 'funnel') {
    const merchantId = str(query, 'merchantId');
    if (merchantId) {
      const funnel = data.funnelByMerchant[merchantId];
      if (!funnel) throw notFound(request, `funnel for ${merchantId} not found`);
      return funnel;
    }
    const all = Object.values(data.funnelByMerchant);
    const sum = (pick: (item: (typeof all)[number]) => number) =>
      all.reduce((total, item) => total + pick(item), 0);
    return {
      merchantId: null,
      from: all[0]?.from ?? new Date().toISOString(),
      to: all[0]?.to ?? new Date().toISOString(),
      events: sum((item) => item.events),
      candidates: sum((item) => item.candidates),
      ambiguous: sum((item) => item.ambiguous),
      aiInvestigated: sum((item) => item.aiInvestigated),
      humanReviewed: sum((item) => item.humanReviewed),
      autoPrepared: sum((item) => item.autoPrepared),
      denied: sum((item) => item.denied),
    };
  }

  throw notFound(request, `no mock route for ${request.method} ${request.path}`);
}

/* ---------------------------------------------------------------- simulator routes */

function resolveSimulator(request: MockRequest): unknown {
  const parts = segments(request.path);
  const [head, second, third] = parts;
  const data = mockDataset;

  if (head === 'runs') {
    if (!second) {
      if (request.method === 'POST') {
        const body = (request.body ?? {}) as Record<string, unknown>;
        const run = {
          runId: `SIM-${String(data.simulationRuns.length + 1).padStart(6, '0')}`,
          seed: Number(body['seed'] ?? 42),
          merchants: Number(body['merchants'] ?? 1),
          transactions: Number(body['transactions'] ?? 100),
          days: Number(body['days'] ?? 1),
          // Basis points on the way in, basis points on the way out (contract 8.5). `disputeRate`
          // is a deprecated alias for the same unit, so it is read as bps too, not scaled.
          disputeRateBps: Math.round(Number(body['disputeRateBps'] ?? body['disputeRate'] ?? 300)),
          failureProfile: (body['failureProfile'] as string) ?? 'NONE',
          scenarioKey: (body['scenarioKey'] as string) ?? null,
          status: 'RUNNING' as const,
          progressPercent: 0,
          eventsPlanned: 0,
          eventsEmitted: 0,
          transactionsCreated: 0,
          evidenceCreated: 0,
          disputesCreated: 0,
          createdAt: new Date().toISOString(),
          startedAt: new Date().toISOString(),
          finishedAt: null,
          requestedBy: (body['requestedBy'] as string) ?? 'console',
          errorMessage: null,
          params: body,
        };
        data.simulationRuns.unshift(run);
        return run;
      }
      return data.simulationRuns;
    }
    const run = data.simulationRuns.find((item) => item.runId === second);
    if (!run) throw notFound(request, `simulation run ${second} not found`);
    if (third === 'stop' && request.method === 'POST') {
      const stopped = { ...run, status: 'STOPPED' as const, finishedAt: new Date().toISOString() };
      const index = data.simulationRuns.findIndex((item) => item.runId === second);
      if (index >= 0) data.simulationRuns[index] = stopped;
      return stopped;
    }
    return run;
  }

  if (head === 'chaos') {
    if (request.method === 'POST') {
      const body = (request.body ?? {}) as Record<string, unknown>;
      const injection = {
        injectionId: `CHA-${String(data.chaosInjections.length + 1).padStart(6, '0')}`,
        runId: (body['runId'] as string) ?? null,
        merchantId: (body['merchantId'] as string) ?? null,
        type: body['type'] as ChaosType,
        status: 'APPLIED' as const,
        target: (body['target'] as Record<string, unknown>) ?? {},
        delayMs: (body['delayMs'] as number) ?? null,
        category: null,
        count: (body['count'] as number) ?? null,
        actor: (body['actor'] as string) ?? 'console',
        injectedAt: new Date().toISOString(),
        completedAt: new Date().toISOString(),
        result: { simulated: true },
        errorMessage: null,
      };
      data.chaosInjections.unshift(injection);
      return injection;
    }
    return data.chaosInjections;
  }

  if (head === 'replay' && request.method === 'POST') {
    const body = (request.body ?? {}) as Record<string, unknown>;
    return {
      replayId: `RPL-${Date.now()}`,
      topic: (body['topic'] as string) ?? 'pdei.canonical.events.v1',
      requestedFrom: String(body['fromOffset'] ?? body['fromTimestamp'] ?? 'earliest'),
      eventsReplayed: 128,
      startedAt: new Date().toISOString(),
    };
  }

  if (head === 'scenarios') {
    if (!second) return data.scenarios;
    if (third === 'run' && request.method === 'POST') {
      const scenario = data.scenarios.find((item) => item.key === second);
      if (!scenario) throw notFound(request, `scenario ${second} not found`);
      const run: SimulationRun = {
        runId: `SIM-${String(data.simulationRuns.length + 1).padStart(6, '0')}`,
        seed: scenario.seed,
        merchants: scenario.merchants,
        transactions: scenario.transactions,
        days: scenario.days,
        disputeRateBps: 900,
        failureProfile: `SCENARIO:${scenario.key}`,
        scenarioKey: scenario.key,
        status: 'RUNNING',
        progressPercent: 0,
        eventsPlanned: 0,
        eventsEmitted: 0,
        transactionsCreated: 0,
        evidenceCreated: 0,
        disputesCreated: 0,
        createdAt: new Date().toISOString(),
        startedAt: new Date().toISOString(),
        finishedAt: null,
        requestedBy: 'console',
        errorMessage: null,
        params: { scenarioKey: scenario.key },
      };
      data.simulationRuns.unshift(run);
      // Enveloped, unlike every other run endpoint (contract 8.5).
      return { run, scenario };
    }
  }

  throw notFound(request, `no mock route for ${request.method} ${request.path}`);
}

/**
 * Entry point used by `lib/api/client.ts`. `path` is relative to whichever base the caller
 * used, so simulator calls are recognised by their own route shapes.
 */
export function resolveMockRequest<T>(request: MockRequest): Promise<T> {
  const simulatorRoots = ['runs', 'chaos', 'replay', 'scenarios'];
  const [head] = segments(request.path);
  const payload = simulatorRoots.includes(head ?? '')
    ? resolveSimulator(request)
    : resolveGateway(request);
  return delay(payload as T);
}
