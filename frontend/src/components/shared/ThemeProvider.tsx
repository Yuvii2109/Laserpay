'use client';

import { useEffect } from 'react';
import { useUiStore } from '@/lib/store/uiStore';

/**
 * Applies the operator's theme preference to <html>.
 *
 * `uiStore` is the single source of truth (persisted under `pdei-ui`); this component only
 * reflects it into the DOM. The pre-paint script in app/layout.tsx applies the same value
 * before first paint so there is no flash, and both write the identical attributes:
 *   - `class="dark"`      -> Tailwind's darkMode: ['class']
 *   - `data-theme`        -> CSS that keys on the attribute directly
 *   - `style.colorScheme` -> native form controls and scrollbars
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const theme = useUiStore((state) => state.theme);

  useEffect(() => {
    const root = document.documentElement;

    const apply = () => {
      const prefersDark =
        theme === 'system'
          ? window.matchMedia('(prefers-color-scheme: dark)').matches
          : theme === 'dark';
      root.classList.toggle('dark', prefersDark);
      root.dataset['theme'] = prefersDark ? 'dark' : 'light';
      root.style.colorScheme = prefersDark ? 'dark' : 'light';
    };

    apply();

    if (theme !== 'system') return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    media.addEventListener('change', apply);
    return () => media.removeEventListener('change', apply);
  }, [theme]);

  return <>{children}</>;
}

/**
 * Inline script injected before hydration. Kept as a string so it can run in <head> and beat
 * first paint. It must stay behaviourally identical to the effect above.
 */
export const THEME_BOOTSTRAP_SCRIPT = `
(function () {
  try {
    var stored = window.localStorage.getItem('pdei-ui');
    var theme = 'system';
    if (stored) {
      var parsed = JSON.parse(stored);
      theme = (parsed && parsed.state && parsed.state.theme) || 'system';
    }
    var dark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    var root = document.documentElement;
    root.classList.toggle('dark', dark);
    root.dataset.theme = dark ? 'dark' : 'light';
    root.style.colorScheme = dark ? 'dark' : 'light';
  } catch (error) {
    /* storage disabled: fall back to the CSS default (light, or OS dark via media query) */
  }
})();
`;
