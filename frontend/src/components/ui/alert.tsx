import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

/**
 * Inline alert. The `warning`/`critical` variants borrow the reserved status ramp and are
 * always rendered with an icon and a title, never colour alone.
 */
export const alertVariants = cva(
  'relative w-full rounded-lg border p-4 text-sm [&>svg]:absolute [&>svg]:left-4 [&>svg]:top-4 [&>svg]:size-4 [&>svg~*]:pl-7',
  {
    variants: {
      variant: {
        default: 'border-border bg-card text-card-foreground',
        info: 'border-primary/30 bg-primary/5 text-foreground [&>svg]:text-primary',
        warning: 'border-[color:var(--status-warning)]/40 bg-[color:var(--status-warning)]/10 text-foreground',
        critical:
          'border-[color:var(--status-critical)]/40 bg-[color:var(--status-critical)]/10 text-foreground',
        good: 'border-[color:var(--status-good)]/40 bg-[color:var(--status-good)]/10 text-foreground',
      },
    },
    defaultVariants: { variant: 'default' },
  },
);

export interface AlertProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof alertVariants> {}

export const Alert = React.forwardRef<HTMLDivElement, AlertProps>(function Alert(
  { className, variant, ...props },
  ref,
) {
  return <div ref={ref} role="alert" className={cn(alertVariants({ variant }), className)} {...props} />;
});

export const AlertTitle = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLHeadingElement>
>(function AlertTitle({ className, ...props }, ref) {
  return <h5 ref={ref} className={cn('mb-1 font-medium leading-none', className)} {...props} />;
});

export const AlertDescription = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLParagraphElement>
>(function AlertDescription({ className, ...props }, ref) {
  return <div ref={ref} className={cn('text-sm text-muted-foreground', className)} {...props} />;
});
