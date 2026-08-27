/**
 * Shared PDEI components. Pages import from `@/components/shared`.
 * Anything domain-shaped that more than one page needs belongs here, not in a page folder.
 */
export { AppShell } from './AppShell';
export { AppSidebar } from './AppSidebar';
// Navigation data is plain (non-client) data - see lib/navigation.ts for why.
export { NAV_SECTIONS, NAV_ITEMS, isNavItemActive, type NavItem, type NavSection } from '@/lib/navigation';
export { TopBar } from './TopBar';
export { ThemeProvider, THEME_BOOTSTRAP_SCRIPT } from './ThemeProvider';
export { MerchantSelector } from './MerchantSelector';
export { PageHeader } from './PageHeader';
export { DataTable, type DataTableColumn, type DataTablePagination } from './DataTable';
export { EmptyState } from './EmptyState';
export { ErrorState } from './ErrorState';
export { LoadingState } from './LoadingState';
export { StatTile, type StatTileDelta } from './StatTile';
export { ReadinessBadge } from './ReadinessBadge';
export { ReadinessMeter } from './ReadinessMeter';
export { EvidenceTypeIcon, EVIDENCE_TYPE_ICON, EVIDENCE_TYPE_LABEL } from './EvidenceTypeIcon';
export { StatusBadge, type StatusKind, type StatusValue } from './StatusBadge';
export { MoneyDisplay } from './MoneyDisplay';
export { TimestampDisplay } from './TimestampDisplay';
export { CopyableId } from './CopyableId';
export { ConnectionIndicator } from './ConnectionIndicator';
export { JsonViewer } from './JsonViewer';
export { ConfirmDialog } from './ConfirmDialog';

/* ---- added by the operational-surfaces pages (control tower, lists, detail views) ---- */
export { HashDisplay } from './HashDisplay';
export { DetailList, type DetailItem } from './DetailList';
export { FilterBar } from './FilterBar';
export { FilterSelect, type FilterOption } from './FilterSelect';
export { SearchInput } from './SearchInput';
export { DeadlineCountdown } from './DeadlineCountdown';
export { LiveEventTicker } from './LiveEventTicker';
export { EvidenceCard, formatBytes } from './EvidenceCard';
export { EventTimeline } from './EventTimeline';
export { EvidenceGraphView } from './EvidenceGraphView';
export { GapList, GAP_TYPE_EXPLANATION } from './GapList';
export { ReadinessBreakdown } from './ReadinessBreakdown';
export { RouteErrorBoundary } from './RouteErrorBoundary';
