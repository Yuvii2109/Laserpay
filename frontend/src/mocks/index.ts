/**
 * Mock layer entry point.
 *
 * Turn it on with NEXT_PUBLIC_USE_MOCKS=true. The api client then routes every request into
 * `resolveMockRequest`, and the control-tower hook drives `startMockSocket`, so the whole
 * console - tables, detail pages, charts, live tail - works with no backend at all.
 *
 * Nothing outside `src/mocks` may import fixture data directly: pages call the same endpoint
 * modules they use against the real gateway. That is what keeps mock mode honest.
 */
export { mockDataset, buildMockDataset, DEFAULT_MOCK_SEED, type MockDataset } from './dataset';
export { resolveMockRequest, type MockRequest } from './router';
export { startMockSocket, type FrameSink } from './socket';
export { createRng, type Rng } from './random';
export {
  requirementsFor,
  baselineRequirements,
  DEFAULT_TOP_REASON_CODES,
  DEFAULT_EXPIRING_SOON_DAYS,
} from './matrix';
