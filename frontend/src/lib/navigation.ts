/**
 * The route map of contract 14, as plain data.
 *
 * This module deliberately has no `'use client'` directive: values exported from a client
 * module become client *references* when a server component imports them (an array turns into
 * an opaque proxy and `.map` is not a function). Navigation data is needed by both sides -
 * the sidebar (client) and `not-found.tsx` (server) - so it lives here.
 */
import {
  FileSearch,
  FlaskConical,
  Gauge,
  LayoutDashboard,
  Receipt,
  ScrollText,
  Settings,
  ShieldAlert,
  Sparkles,
  type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  /** One line on what the route answers; shown in the collapsed sidebar tooltip. */
  description: string;
  /** Extra path prefixes that should light this item up. */
  matches?: string[];
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

/** Every navigable route of contract 14, grouped by the question it answers. */
export const NAV_SECTIONS: NavSection[] = [
  {
    title: 'Operate',
    items: [
      {
        href: '/control-tower',
        label: 'Control Tower',
        icon: LayoutDashboard,
        description: 'Readiness, at-risk feed and open exposure for the selected merchant',
      },
      {
        href: '/transactions',
        label: 'Transactions',
        icon: Receipt,
        description: 'Every transaction with its readiness band and evidence count',
      },
      {
        href: '/evidence',
        label: 'Evidence',
        icon: FileSearch,
        description: 'Full-text evidence explorer with type, status and provenance',
      },
    ],
  },
  {
    title: 'Defend',
    items: [
      {
        href: '/disputes',
        label: 'Disputes',
        icon: ShieldAlert,
        description: 'Disputes by status, reason code and deadline',
      },
      {
        href: '/cases',
        label: 'Cases',
        icon: Sparkles,
        description: 'Case queue in workflow swimlanes, and the Case X-Ray',
      },
      {
        href: '/policies',
        label: 'Policies',
        icon: ScrollText,
        description: 'Versioned requirement matrix and automation thresholds',
      },
    ],
  },
  {
    title: 'Verify',
    items: [
      {
        href: '/simulation',
        label: 'Simulation',
        icon: FlaskConical,
        description: 'Seeded workload runs and chaos injections',
      },
      {
        href: '/observability',
        label: 'Observability',
        icon: Gauge,
        description: 'The events-to-human funnel and platform metrics',
      },
      {
        href: '/settings',
        label: 'Settings',
        icon: Settings,
        description: 'Merchant and service configuration',
      },
    ],
  },
];

/** Flattened route list, useful for command palettes and tests. */
export const NAV_ITEMS: NavItem[] = NAV_SECTIONS.flatMap((section) => section.items);

/** True when `pathname` belongs to `item` (exact match, child route, or declared prefix). */
export function isNavItemActive(pathname: string, item: NavItem): boolean {
  if (pathname === item.href) return true;
  if (pathname.startsWith(`${item.href}/`)) return true;
  return (item.matches ?? []).some((prefix) => pathname.startsWith(prefix));
}
