'use client';

import { useEffect } from 'react';
import { AppSidebar } from './AppSidebar';
import { TopBar } from './TopBar';
import { useUiStore } from '@/lib/store/uiStore';
import { useControlTowerSocket } from '@/lib/ws/useControlTowerSocket';
import { useInvalidateOnWsEvent } from '@/lib/query/useInvalidateOnWsEvent';
import { useLiveStore } from '@/lib/store/liveStore';

/**
 * The application chrome: sidebar, top bar, and the single live connection for the session.
 *
 * Exactly one control-tower socket exists per browser tab and it lives here, so page code
 * never opens its own. Frames land in `liveStore`; `useInvalidateOnWsEvent` turns them into
 * TanStack Query invalidations, which is the only path by which live events change what is
 * displayed - the socket never writes domain state directly.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const merchantId = useUiStore((state) => state.selectedMerchantId);
  const { reconnect } = useControlTowerSocket({ merchantId });

  useInvalidateOnWsEvent();

  // Leaving the tab does not tear the socket down; coming back should not show a stale tail.
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return;
      const { status } = useLiveStore.getState();
      if (status === 'closed' || status === 'error') reconnect();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [reconnect]);

  return (
    <div className="flex h-dvh w-full overflow-hidden bg-background">
      <AppSidebar className="hidden md:flex" />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar onReconnect={reconnect} />
        <main className="flex-1 overflow-y-auto scrollbar-thin">
          <div className="mx-auto w-full max-w-[110rem] px-4 py-5 lg:px-6">{children}</div>
        </main>
      </div>
    </div>
  );
}
