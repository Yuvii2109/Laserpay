/**
 * UI state: what the operator is looking at, not what the platform knows.
 *
 * Server state lives in TanStack Query; anything here is a view preference and is persisted
 * to localStorage under `pdei-ui` so a reload lands the operator back where they were.
 * The theme value is also read by the inline pre-paint script in app/layout.tsx, so the
 * storage key and shape below are load-bearing - change them in both places or not at all.
 */
'use client';

import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { ReadinessBand } from '@/lib/types/readiness';
import type { EvidenceStatus, EvidenceType } from '@/lib/types/evidence';
import type { DisputeReasonCode, DisputeStatus } from '@/lib/types/dispute';
import type { CaseStatus } from '@/lib/types/case';
import type { GapSeverity, GapType } from '@/lib/types/readiness';

export const UI_STORAGE_KEY = 'pdei-ui';

export type ThemePreference = 'light' | 'dark' | 'system';
export type TableDensity = 'comfortable' | 'compact';
export type TimeRangePreset = '24h' | '7d' | '30d' | '90d' | 'all';

/** Cross-page filter bag. Every field is optional; `undefined` means "no filter". */
export interface UiFilters {
  search?: string;
  band?: ReadinessBand;
  evidenceType?: EvidenceType;
  evidenceStatus?: EvidenceStatus;
  disputeStatus?: DisputeStatus;
  reasonCode?: DisputeReasonCode;
  caseStatus?: CaseStatus;
  gapType?: GapType;
  gapSeverity?: GapSeverity;
  range?: TimeRangePreset;
}

export interface UiState {
  /** `null` until a merchant list has loaded; every merchant-scoped query waits on it. */
  selectedMerchantId: string | null;
  theme: ThemePreference;
  density: TableDensity;
  sidebarCollapsed: boolean;
  /** Absolute timestamps are rendered in UTC by default (audit-safe). */
  timeZoneMode: 'utc' | 'local';
  filters: UiFilters;
  /** Page size shared by every DataTable. */
  pageSize: number;

  setSelectedMerchantId: (merchantId: string | null) => void;
  setTheme: (theme: ThemePreference) => void;
  setDensity: (density: TableDensity) => void;
  toggleSidebar: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setTimeZoneMode: (mode: 'utc' | 'local') => void;
  setFilter: <K extends keyof UiFilters>(key: K, value: UiFilters[K]) => void;
  setFilters: (filters: UiFilters) => void;
  resetFilters: () => void;
  setPageSize: (size: number) => void;
}

const DEFAULT_FILTERS: UiFilters = { range: '30d' };

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      selectedMerchantId: null,
      theme: 'system',
      density: 'comfortable',
      sidebarCollapsed: false,
      timeZoneMode: 'utc',
      filters: DEFAULT_FILTERS,
      pageSize: 25,

      setSelectedMerchantId: (merchantId) =>
        set((state) =>
          state.selectedMerchantId === merchantId
            ? state
            : // Switching merchant clears row-level filters; the range preset survives.
              {
                selectedMerchantId: merchantId,
                filters: { range: state.filters.range ?? DEFAULT_FILTERS.range },
              },
        ),
      setTheme: (theme) => set({ theme }),
      setDensity: (density) => set({ density }),
      toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      setTimeZoneMode: (timeZoneMode) => set({ timeZoneMode }),
      setFilter: (key, value) =>
        set((state) => ({ filters: { ...state.filters, [key]: value } })),
      setFilters: (filters) => set({ filters }),
      resetFilters: () => set({ filters: DEFAULT_FILTERS }),
      setPageSize: (pageSize) => set({ pageSize: Math.max(5, Math.min(pageSize, 200)) }),
    }),
    {
      name: UI_STORAGE_KEY,
      version: 1,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({
        selectedMerchantId: state.selectedMerchantId,
        theme: state.theme,
        density: state.density,
        sidebarCollapsed: state.sidebarCollapsed,
        timeZoneMode: state.timeZoneMode,
        pageSize: state.pageSize,
      }),
    },
  ),
);

/* ---- selectors (stable references; use these instead of selecting the whole store) ---- */

export const selectMerchantId = (state: UiState) => state.selectedMerchantId;
export const selectDensity = (state: UiState) => state.density;
export const selectFilters = (state: UiState) => state.filters;
export const selectTheme = (state: UiState) => state.theme;
export const selectTimeZoneMode = (state: UiState) => state.timeZoneMode;

/** Convenience hook: the currently selected merchant id (or null). */
export function useSelectedMerchantId(): string | null {
  return useUiStore(selectMerchantId);
}

/** Resolves a range preset into an ISO `from` bound (null for `all`). */
export function rangeToFrom(range: TimeRangePreset | undefined, now: Date = new Date()): string | null {
  switch (range) {
    case '24h':
      return new Date(now.getTime() - 86_400_000).toISOString();
    case '7d':
      return new Date(now.getTime() - 7 * 86_400_000).toISOString();
    case '30d':
      return new Date(now.getTime() - 30 * 86_400_000).toISOString();
    case '90d':
      return new Date(now.getTime() - 90 * 86_400_000).toISOString();
    default:
      return null;
  }
}

export const TIME_RANGE_LABEL: Readonly<Record<TimeRangePreset, string>> = {
  '24h': 'Last 24 hours',
  '7d': 'Last 7 days',
  '30d': 'Last 30 days',
  '90d': 'Last 90 days',
  all: 'All time',
};
