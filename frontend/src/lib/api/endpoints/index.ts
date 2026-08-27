/**
 * Endpoint barrel. One module per resource; every exported function maps to exactly one
 * route of contract 8.1 (gateway) or 8.5 (simulator). Pages import from `@/lib/api/endpoints`
 * and never build URLs by hand.
 */
export { merchantsApi, type DependencyStatus, type GatewayReadiness } from './merchants';
export { transactionsApi } from './transactions';
export { evidenceApi } from './evidence';
export { disputesApi } from './disputes';
export { casesApi } from './cases';
export { investigationsApi } from './investigations';
export { policiesApi } from './policies';
export { auditApi } from './audit';
export { gapsApi } from './gaps';
export { metricsApi } from './metrics';
export { simulationApi } from './simulation';
