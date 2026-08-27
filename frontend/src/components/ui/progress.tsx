'use client';

import * as React from 'react';
import * as ProgressPrimitive from '@radix-ui/react-progress';
import { cn } from '@/lib/utils';

export interface ProgressProps
  extends React.ComponentPropsWithoutRef<typeof ProgressPrimitive.Root> {
  /** 0-100. */
  value?: number;
  /** Colour of the filled portion; pass a status/band token, never a raw hex. */
  indicatorColor?: string;
  indicatorClassName?: string;
}

export const Progress = React.forwardRef<
  React.ElementRef<typeof ProgressPrimitive.Root>,
  ProgressProps
>(function Progress({ className, value = 0, indicatorColor, indicatorClassName, ...props }, ref) {
  const clamped = Math.min(100, Math.max(0, value));
  return (
    <ProgressPrimitive.Root
      ref={ref}
      value={clamped}
      className={cn('relative h-2 w-full overflow-hidden rounded-full bg-muted', className)}
      {...props}
    >
      <ProgressPrimitive.Indicator
        className={cn('h-full w-full flex-1 rounded-full transition-transform', indicatorClassName)}
        style={{
          transform: `translateX(-${100 - clamped}%)`,
          backgroundColor: indicatorColor ?? 'hsl(var(--primary))',
        }}
      />
    </ProgressPrimitive.Root>
  );
});
