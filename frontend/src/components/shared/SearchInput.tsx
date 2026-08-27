'use client';

import * as React from 'react';
import { Search, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

export interface SearchInputProps {
  /** Required: the label is bound to the input with `htmlFor`/`id`. */
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** Keystrokes are coalesced for this long before the query is issued. */
  debounceMs?: number;
  className?: string;
  inputClassName?: string;
  /** Hide the visible label but keep it for assistive technology. */
  hideLabel?: boolean;
  /** Extra hint rendered under the field, e.g. the FTS syntax. */
  hint?: React.ReactNode;
}

/**
 * A debounced, labelled search field.
 *
 * Every keystroke would otherwise be a request against `GET /transactions?q=` or the Postgres
 * FTS behind `GET /evidence?q=`; the debounce keeps the query key stable long enough for
 * TanStack Query to cache a result rather than thrash it. Submitting the enclosing form (or
 * pressing Enter) flushes immediately, because a deliberate Enter should not wait 300 ms.
 */
export function SearchInput({
  id,
  label,
  value,
  onChange,
  placeholder = 'Search',
  debounceMs = 300,
  className,
  inputClassName,
  hideLabel = false,
  hint,
}: SearchInputProps) {
  const [draft, setDraft] = React.useState(value);
  const timerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const onChangeRef = React.useRef(onChange);
  onChangeRef.current = onChange;

  // An external reset (merchant switch, "clear filters") must win over the local draft.
  React.useEffect(() => {
    setDraft(value);
  }, [value]);

  React.useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    },
    [],
  );

  const schedule = (next: string) => {
    setDraft(next);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => onChangeRef.current(next), debounceMs);
  };

  const flush = (next: string) => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    setDraft(next);
    onChangeRef.current(next);
  };

  return (
    <div className={cn('flex min-w-0 flex-col gap-1.5', className)}>
      <Label htmlFor={id} className={cn(hideLabel && 'sr-only')}>
        {label}
      </Label>
      <div className="relative">
        <Search
          className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden
        />
        <Input
          id={id}
          type="search"
          role="searchbox"
          value={draft}
          placeholder={placeholder}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => schedule(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              flush(event.currentTarget.value);
            }
            if (event.key === 'Escape' && draft) {
              event.preventDefault();
              flush('');
            }
          }}
          className={cn('pl-8', draft ? 'pr-8' : undefined, inputClassName)}
        />
        {draft ? (
          <button
            type="button"
            onClick={() => flush('')}
            aria-label={`Clear ${label.toLowerCase()}`}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:text-foreground"
          >
            <X className="size-3.5" aria-hidden />
          </button>
        ) : null}
      </div>
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}
