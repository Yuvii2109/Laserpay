/**
 * The service registry of contract 2, as plain data.
 *
 * The browser cannot probe these directly - each actuator sits on its own origin with no CORS
 * grant - and `GET /health/ready` reports on the gateway's *infrastructure* dependencies, not on
 * its sibling services. So this registry supplies addresses and roles, the gateway row takes its
 * state from `/health/ready`, and every other row is marked "not probed" rather than being drawn
 * green on no evidence. The dependency map is rendered separately (see `INFRA_DEPENDENCIES`).
 */

export type ServiceKind = 'gateway' | 'web' | 'worker' | 'python' | 'frontend' | 'infra';

export interface ServiceEntry {
  /** Module name; also the OTEL service name (contract 13). */
  name: string;
  kind: ServiceKind;
  hostPort: number;
  healthPath: string;
  role: string;
}

export const PDEI_SERVICES: readonly ServiceEntry[] = [
  {
    name: 'api-gateway-service',
    kind: 'gateway',
    hostPort: 8080,
    healthPath: '/actuator/health',
    role: 'Every REST route and the control-tower WebSocket.',
  },
  {
    name: 'ingestion-service',
    kind: 'web',
    hostPort: 8081,
    healthPath: '/actuator/health',
    role: 'Raw event intake and webhooks onto pdei.raw.events.v1.',
  },
  {
    name: 'normalization-worker',
    kind: 'worker',
    hostPort: 8082,
    healthPath: '/actuator/health',
    role: 'Raw envelopes to canonical events.',
  },
  {
    name: 'state-builder-worker',
    kind: 'worker',
    hostPort: 8083,
    healthPath: '/actuator/health',
    role: 'Canonical events to transaction, evidence and dispute state.',
  },
  {
    name: 'readiness-worker',
    kind: 'worker',
    hostPort: 8084,
    healthPath: '/actuator/health',
    role: 'Contract 7 scoring and gap detection.',
  },
  {
    name: 'case-orchestrator-service',
    kind: 'worker',
    hostPort: 8085,
    healthPath: '/actuator/health',
    role: 'DisputeCaseWorkflow on the pdei-dispute-cases task queue.',
  },
  {
    name: 'document-processor-service',
    kind: 'worker',
    hostPort: 8086,
    healthPath: '/actuator/health',
    role: 'Extraction and re-hashing of uploaded documents.',
  },
  {
    name: 'audit-service',
    kind: 'worker',
    hostPort: 8087,
    healthPath: '/actuator/health',
    role: 'The hash-chained audit log.',
  },
  {
    name: 'simulator-service',
    kind: 'web',
    hostPort: 8088,
    healthPath: '/actuator/health',
    role: 'Seeded workloads, chaos injection and replay.',
  },
  {
    name: 'ai-reasoning-service',
    kind: 'python',
    hostPort: 8000,
    healthPath: '/health',
    role: 'The only place model code runs. Never called from the browser.',
  },
  {
    name: 'frontend (pdei-web)',
    kind: 'frontend',
    hostPort: 3000,
    healthPath: '/api/health',
    role: 'This console.',
  },
];

/**
 * The dependency keys `ReadinessProbeService.probe()` puts in `HealthResponse.dependencies`,
 * in the order the probe writes them. Postgres is the only required one: without it no route
 * works, and the gateway answers 503.
 */
export interface InfraDependency {
  /** Key in `GatewayReadiness.dependencies`. */
  key: string;
  label: string;
  hostPort: number;
  required: boolean;
  role: string;
}

export const INFRA_DEPENDENCIES: readonly InfraDependency[] = [
  {
    key: 'postgres',
    label: 'PostgreSQL',
    hostPort: 5432,
    required: true,
    role: 'Schema pdei. Every read route reads it; 503 when it is unreachable.',
  },
  {
    key: 'redis',
    label: 'Redis',
    hostPort: 6379,
    required: false,
    role: 'Idempotency, cached snapshots, rate limits and the AI budget (contract 12).',
  },
  {
    key: 'kafka',
    label: 'Kafka',
    hostPort: 29092,
    required: false,
    role: 'The eight canonical topics; the gateway consumes readiness and case events.',
  },
  {
    key: 'objectStore',
    label: 'MinIO',
    hostPort: 9000,
    required: false,
    role: 'Buckets pdei-evidence and pdei-packages, versioning on (contract 11).',
  },
];

export interface ExternalConsole {
  label: string;
  url: string;
  detail: string;
  /** Marks the two consoles contract 14 asks this page to link to prominently. */
  primary: boolean;
}

/** Infrastructure consoles from contract 2. Grafana and Temporal are the headline pair. */
export const EXTERNAL_CONSOLES: readonly ExternalConsole[] = [
  {
    label: 'Grafana',
    url: 'http://localhost:3001',
    detail: 'Dashboards over the pdei_* metric family (contract 13). admin / admin in dev.',
    primary: true,
  },
  {
    label: 'Temporal UI',
    url: 'http://localhost:8233',
    detail: 'Every DisputeCaseWorkflow execution, its activities, signals and failures.',
    primary: true,
  },
  {
    label: 'Prometheus',
    url: 'http://localhost:9090',
    detail: 'Raw metric queries; scrapes /actuator/prometheus on every Spring service.',
    primary: false,
  },
  {
    label: 'Kafka UI',
    url: 'http://localhost:8090',
    detail: 'Topics, partitions, consumer lag and the DLQ.',
    primary: false,
  },
  {
    label: 'MinIO console',
    url: 'http://localhost:9001',
    detail: 'The pdei-evidence and pdei-packages buckets, with object versions.',
    primary: false,
  },
];

/** Metric names the console points operators at, straight from contract 13. */
export const HEADLINE_METRICS: readonly { name: string; meaning: string }[] = [
  { name: 'pdei_events_processed_total', meaning: 'Throughput per service, type and outcome.' },
  { name: 'pdei_events_duplicate_total', meaning: 'Idempotency doing its job under replay.' },
  { name: 'pdei_kafka_consumer_lag', meaning: 'Backpressure per group and topic.' },
  { name: 'pdei_readiness_score', meaning: 'Readiness distribution per merchant.' },
  { name: 'pdei_ai_admission_total', meaning: 'How many candidates admission control let through.' },
  { name: 'pdei_ai_unsupported_claims_total', meaning: 'Invented citations caught by the gate.' },
  { name: 'pdei_policy_gate_total', meaning: 'ALLOW / ALLOW_WITH_REVIEW / DENY volumes.' },
  { name: 'pdei_chaos_injections_total', meaning: 'Failures deliberately introduced.' },
];
