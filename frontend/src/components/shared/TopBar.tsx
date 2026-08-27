'use client';

import { useQueryClient } from '@tanstack/react-query';
import { Clock, Monitor, Moon, RefreshCw, Rows3, Rows4, Sun } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { toast } from '@/components/ui/sonner';
import { useUiStore, type ThemePreference } from '@/lib/store/uiStore';
import { queryKeys } from '@/lib/query/keys';
import { MerchantSelector } from './MerchantSelector';
import { ConnectionIndicator } from './ConnectionIndicator';

export interface TopBarProps {
  className?: string;
  /** Wired to the control-tower socket by the app shell. */
  onReconnect?: () => void;
}

const THEME_ICON = { light: Sun, dark: Moon, system: Monitor } as const;

/**
 * The app shell's top bar: merchant scope, live connection state, and the view preferences
 * that belong to the operator rather than to any one page.
 */
export function TopBar({ className, onReconnect }: TopBarProps) {
  const queryClient = useQueryClient();
  const theme = useUiStore((state) => state.theme);
  const setTheme = useUiStore((state) => state.setTheme);
  const density = useUiStore((state) => state.density);
  const setDensity = useUiStore((state) => state.setDensity);
  const timeZoneMode = useUiStore((state) => state.timeZoneMode);
  const setTimeZoneMode = useUiStore((state) => state.setTimeZoneMode);

  const ThemeIcon = THEME_ICON[theme];
  const DensityIcon = density === 'compact' ? Rows4 : Rows3;

  const refreshAll = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.all });
    toast.success('Refreshing every view');
  };

  return (
    <header
      className={cn(
        'flex h-14 items-center justify-between gap-3 border-b border-border bg-card px-4',
        className,
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <MerchantSelector />
      </div>

      <div className="flex items-center gap-1.5">
        <ConnectionIndicator onReconnect={onReconnect} />

        <Tooltip>
          <TooltipTrigger asChild>
            <Button variant="ghost" size="icon-sm" onClick={refreshAll} aria-label="Refresh all data">
              <RefreshCw className="size-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent side="bottom">Refetch every open query</TooltipContent>
        </Tooltip>

        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => setDensity(density === 'compact' ? 'comfortable' : 'compact')}
              aria-label={density === 'compact' ? 'Use comfortable rows' : 'Use compact rows'}
            >
              <DensityIcon className="size-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            {density === 'compact' ? 'Compact rows' : 'Comfortable rows'}
          </TooltipContent>
        </Tooltip>

        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => setTimeZoneMode(timeZoneMode === 'utc' ? 'local' : 'utc')}
              aria-label={timeZoneMode === 'utc' ? 'Show times in local zone' : 'Show times in UTC'}
            >
              <Clock className="size-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            Timestamps: {timeZoneMode === 'utc' ? 'UTC' : 'local zone'}
          </TooltipContent>
        </Tooltip>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label="Theme">
              <ThemeIcon className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel>Theme</DropdownMenuLabel>
            <DropdownMenuRadioGroup
              value={theme}
              onValueChange={(value) => setTheme(value as ThemePreference)}
            >
              <DropdownMenuRadioItem value="light">Light</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="dark">Dark</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="system">System</DropdownMenuRadioItem>
            </DropdownMenuRadioGroup>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={refreshAll}>Refresh all data</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
