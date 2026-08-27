'use client';

import { Toaster as SonnerToaster, toast } from 'sonner';
import { useUiStore } from '@/lib/store/uiStore';

/**
 * Toast host. Mounted once in the app shell. Theme follows the operator's preference so a
 * toast never flashes a light card onto a dark console.
 */
export function Toaster() {
  const theme = useUiStore((state) => state.theme);
  return (
    <SonnerToaster
      theme={theme}
      position="bottom-right"
      closeButton
      richColors={false}
      toastOptions={{
        classNames: {
          toast: 'border border-border bg-card text-card-foreground shadow-lg',
          description: 'text-muted-foreground',
          actionButton: 'bg-primary text-primary-foreground',
          cancelButton: 'bg-muted text-muted-foreground',
        },
      }}
    />
  );
}

export { toast };
