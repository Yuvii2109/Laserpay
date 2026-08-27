'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Activity, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useUiStore } from '@/lib/store/uiStore';
import { config } from '@/lib/config';
import { NAV_SECTIONS, isNavItemActive } from '@/lib/navigation';

export function AppSidebar({ className }: { className?: string }) {
  const pathname = usePathname() ?? '/';
  const collapsed = useUiStore((state) => state.sidebarCollapsed);
  const toggleSidebar = useUiStore((state) => state.toggleSidebar);

  return (
    <aside
      className={cn(
        'flex h-full flex-col border-r border-sidebar-border bg-sidebar transition-[width] duration-200',
        collapsed ? 'w-[4.25rem]' : 'w-60',
        className,
      )}
      data-collapsed={collapsed}
    >
      <div className="flex h-14 items-center gap-2 border-b border-sidebar-border px-3">
        <Link href="/control-tower" className="flex min-w-0 items-center gap-2">
          <span
            className="flex size-8 shrink-0 items-center justify-center rounded-md text-sm font-bold text-primary-foreground"
            style={{ backgroundColor: 'var(--chart-1)' }}
            aria-hidden
          >
            P
          </span>
          {collapsed ? null : (
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold text-foreground">
                {config.appName}
              </span>
              <span className="block truncate text-2xs text-muted-foreground">
                Pre-Dispute Evidence Intelligence
              </span>
            </span>
          )}
        </Link>
      </div>

      <nav className="flex-1 overflow-y-auto scrollbar-thin px-2 py-3" aria-label="Primary">
        {NAV_SECTIONS.map((section) => (
          <div key={section.title} className="mb-4 last:mb-0">
            {collapsed ? (
              <div className="mx-2 mb-2 h-px bg-sidebar-border" aria-hidden />
            ) : (
              <p className="px-2 pb-1.5 text-2xs font-medium uppercase tracking-wide text-muted-foreground">
                {section.title}
              </p>
            )}
            <ul className="space-y-0.5">
              {section.items.map((item) => {
                const active = isNavItemActive(pathname, item);
                const Icon = item.icon;
                const link = (
                  <Link
                    href={item.href}
                    aria-current={active ? 'page' : undefined}
                    className={cn(
                      'flex items-center gap-2.5 rounded-md px-2 py-2 text-sm transition-colors',
                      active
                        ? 'bg-sidebar-accent font-medium text-sidebar-accent-foreground'
                        : 'text-sidebar-foreground hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground',
                      collapsed && 'justify-center px-0',
                    )}
                  >
                    <Icon className="size-4 shrink-0" aria-hidden />
                    {collapsed ? <span className="sr-only">{item.label}</span> : <span>{item.label}</span>}
                  </Link>
                );

                return (
                  <li key={item.href}>
                    {collapsed ? (
                      <Tooltip>
                        <TooltipTrigger asChild>{link}</TooltipTrigger>
                        <TooltipContent side="right">
                          <p className="font-medium">{item.label}</p>
                          <p className="text-muted-foreground">{item.description}</p>
                        </TooltipContent>
                      </Tooltip>
                    ) : (
                      link
                    )}
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="border-t border-sidebar-border p-2">
        <button
          type="button"
          onClick={toggleSidebar}
          className="flex w-full items-center gap-2 rounded-md px-2 py-2 text-sm text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-foreground"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? (
            <PanelLeftOpen className="size-4 shrink-0" aria-hidden />
          ) : (
            <>
              <PanelLeftClose className="size-4 shrink-0" aria-hidden />
              <span>Collapse</span>
            </>
          )}
        </button>
        {collapsed ? null : (
          <p className="flex items-center gap-1.5 px-2 pt-2 text-2xs text-muted-foreground">
            <Activity className="size-3" aria-hidden />
            {config.useMocks ? 'Mock fixtures' : 'Live gateway'}
          </p>
        )}
      </div>
    </aside>
  );
}
