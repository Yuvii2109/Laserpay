import Link from 'next/link';
import { Compass } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/shared/EmptyState';
import { NAV_SECTIONS } from '@/lib/navigation';

/** 404 inside the shell, with the route map so an operator is never stranded. */
export default function NotFound() {
  return (
    <div className="mx-auto max-w-2xl py-10">
      <EmptyState
        icon={Compass}
        title="This route does not exist"
        description="The address is not part of the PDEI console. Pick a destination below, or head back to the Control Tower."
        action={
          <Button asChild>
            <Link href="/control-tower">Go to Control Tower</Link>
          </Button>
        }
      />
      <div className="mt-8 grid gap-6 sm:grid-cols-3">
        {NAV_SECTIONS.map((section) => (
          <div key={section.title}>
            <p className="pb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {section.title}
            </p>
            <ul className="space-y-1.5">
              {section.items.map((item) => (
                <li key={item.href}>
                  <Link href={item.href} className="text-sm text-foreground underline-offset-4 hover:underline">
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}
