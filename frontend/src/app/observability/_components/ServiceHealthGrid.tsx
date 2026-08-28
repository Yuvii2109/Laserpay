'use client';

import { CircleHelp, ExternalLink, Radio, type LucideIcon } from 'lucide-react';
import { CircleCheck, OctagonX, AlertTriangle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { CONNECTION_LABEL } from '@/lib/types/ws';
import { useLiveStore } from '@/lib/store/liveStore';
import type { DependencyStatus, GatewayReadiness } from '@/lib/api/endpoints';
import { INFRA_DEPENDENCIES, PDEI_SERVICES, type ServiceEntry } from './services';

type Health = 'up' | 'down' | 'degraded' | 'unknown';

const HEALTH_ICON: Readonly<Record<Health, LucideIcon>> = {
  up: CircleCheck,
  down: OctagonX,
  degraded: AlertTriangle,
  unknown: CircleHelp,
};

const HEALTH_COLOR: Readonly<Record<Health, string>> = {
  up: 'var(--status-good)',
  down: 'var(--status-critical)',
  degraded: 'var(--status-warning)',
  unknown: 'var(--status-neutral)',
};

const HEALTH_LABEL: Readonly<Record<Health, string>> = {
  up: 'Up',
  down: 'Down',
  degraded: 'Degraded',
  unknown: 'Not probed',
};

/** `HealthResponse.status` -> a health state for the gateway row. */
function healthFromStatus(status: GatewayReadiness['status'] | undefined): Health {
  if (!status) return 'unknown';
  if (status === 'READY') return 'up';
  if (status === 'DEGRADED') return 'degraded';
  return 'down';
}

/** `HealthResponse.dependencies` value -> a health state for one dependency row. */
function healthFromDependency(status: DependencyStatus | undefined): Health {
  if (status === 'UP') return 'up';
  if (status === 'DOWN') return 'down';
  return 'unknown';
}

export interface ServiceHealthGridProps {
  readiness: GatewayReadiness | undefined;
  gatewayReachable: boolean;
  className?: string;
}

/**
 * Platform health as far as the browser can honestly report it.
 *
 * Three signals actually reach a browser: the gateway's own `/health/ready` status, the
 * `dependencies` map inside it - which the gateway probes itself, keyed by infrastructure
 * component (postgres, redis, kafka, objectStore) and NOT by service name - and the state of the
 * control-tower socket. Every other service is listed with its contract 2 address and a link to
 * its actuator, and is explicitly marked "not probed" rather than drawn green on no evidence.
 */
export function ServiceHealthGrid({
  readiness,
  gatewayReachable,
  className,
}: ServiceHealthGridProps) {
  const socketStatus = useLiveStore((state) => state.status);
  const dependencies = readiness?.dependencies ?? {};

  const healthFor = (service: ServiceEntry): Health => {
    if (service.name === 'frontend (pdei-web)') return 'up';
    if (service.kind === 'gateway') {
      return gatewayReachable ? healthFromStatus(readiness?.status) : 'down';
    }
    // Nothing reports on the other services to a browser, so nothing is claimed about them.
    return 'unknown';
  };

  return (
    <section className={cn('space-y-3', className)} aria-label="Service health">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-foreground">Service health</h2>
        <span className="text-2xs text-muted-foreground">
          from <span className="mono-id">GET /health/ready</span> · the browser cannot reach other
          actuators across origins
        </span>
      </div>

      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {PDEI_SERVICES.map((service) => {
          const health = healthFor(service);
          const Icon = HEALTH_ICON[health];
          const color = HEALTH_COLOR[health];
          return (
            <article
              key={service.name}
              className="surface-card flex flex-col gap-1.5 p-3"
              aria-label={`${service.name}: ${HEALTH_LABEL[health]}`}
            >
              <div className="flex items-start justify-between gap-2">
                <span className="mono-id truncate text-xs text-foreground">{service.name}</span>
                <span
                  className="inline-flex shrink-0 items-center gap-1 text-2xs font-medium"
                  style={{ color }}
                >
                  <Icon className="size-3.5" aria-hidden />
                  {HEALTH_LABEL[health]}
                </span>
              </div>
              <p className="text-2xs leading-snug text-muted-foreground">{service.role}</p>
              <div className="mt-auto flex items-center justify-between gap-2 pt-1">
                <Badge variant="subtle" className="tabular text-2xs">
                  :{service.hostPort}
                </Badge>
                <a
                  href={`http://localhost:${service.hostPort}${service.healthPath}`}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-2xs text-primary underline-offset-4 hover:underline"
                >
                  {service.healthPath}
                  <ExternalLink className="size-3" aria-hidden />
                </a>
              </div>
            </article>
          );
        })}

        <article className="surface-card flex flex-col gap-1.5 p-3" aria-label="Control-tower socket">
          <div className="flex items-start justify-between gap-2">
            <span className="mono-id truncate text-xs text-foreground">ws/control-tower</span>
            <span
              className="inline-flex shrink-0 items-center gap-1 text-2xs font-medium"
              style={{
                color:
                  socketStatus === 'open'
                    ? 'var(--status-good)'
                    : socketStatus === 'mock'
                      ? 'var(--chart-7)'
                      : socketStatus === 'connecting' || socketStatus === 'reconnecting'
                        ? 'var(--status-warning)'
                        : 'var(--status-critical)',
              }}
            >
              <Radio className="size-3.5" aria-hidden />
              {CONNECTION_LABEL[socketStatus]}
            </span>
          </div>
          <p className="text-2xs leading-snug text-muted-foreground">
            The one push channel into this console. Every frame becomes a query invalidation, never
            a direct state write.
          </p>
          <div className="mt-auto pt-1">
            <Badge variant="subtle" className="tabular text-2xs">
              :8080
            </Badge>
          </div>
        </article>
      </div>

      <div className="flex flex-wrap items-baseline justify-between gap-2 pt-1">
        <h3 className="text-xs font-semibold text-foreground">Gateway dependencies</h3>
        <span className="text-2xs text-muted-foreground">
          the four components <span className="mono-id">/health/ready</span> actually probes
        </span>
      </div>

      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
        {INFRA_DEPENDENCIES.map((dependency) => {
          const health = readiness
            ? healthFromDependency(dependencies[dependency.key])
            : 'unknown';
          const Icon = HEALTH_ICON[health];
          const color = HEALTH_COLOR[health];
          const isDegraded = (readiness?.degraded ?? []).includes(dependency.key);
          return (
            <article
              key={dependency.key}
              className="surface-card flex flex-col gap-1.5 p-3"
              aria-label={`${dependency.label}: ${HEALTH_LABEL[health]}`}
            >
              <div className="flex items-start justify-between gap-2">
                <span className="mono-id truncate text-xs text-foreground">{dependency.key}</span>
                <span
                  className="inline-flex shrink-0 items-center gap-1 text-2xs font-medium"
                  style={{ color }}
                >
                  <Icon className="size-3.5" aria-hidden />
                  {HEALTH_LABEL[health]}
                </span>
              </div>
              <p className="text-2xs leading-snug text-muted-foreground">{dependency.role}</p>
              <div className="mt-auto flex items-center justify-between gap-2 pt-1">
                <Badge variant="subtle" className="tabular text-2xs">
                  :{dependency.hostPort}
                </Badge>
                <span className="text-2xs text-muted-foreground">
                  {dependency.required
                    ? 'required'
                    : isDegraded
                      ? 'down - degraded, not fatal'
                      : 'optional'}
                </span>
              </div>
            </article>
          );
        })}
      </div>

      <p className="text-2xs text-muted-foreground">
        &ldquo;Not probed&rdquo; means exactly that. <span className="mono-id">/health/ready</span>{' '}
        reports the gateway&rsquo;s own status and the four infrastructure components above; it
        does not - and cannot - speak for the other services, and the browser is not allowed to
        ask them directly. Use Grafana or the actuator links above for the authoritative answer.
      </p>
    </section>
  );
}
