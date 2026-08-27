/**
 * Seeded PRNG for the fixture module. Same seed in, same dataset out - the frontend mirror of
 * contract 17 rule 11 ("reproducible workloads via deterministic seeds").
 *
 * mulberry32: 32-bit state, uniform enough for fixtures, ~10 lines, no dependency.
 */

export interface Rng {
  /** Float in [0,1). */
  next(): number;
  /** Integer in [min,max] inclusive. */
  int(min: number, max: number): number;
  /** True with probability `p`. */
  chance(p: number): boolean;
  pick<T>(items: readonly T[]): T;
  /** `count` distinct items, in list order. */
  sample<T>(items: readonly T[], count: number): T[];
  shuffle<T>(items: readonly T[]): T[];
  /** Lowercase hex string of `length` characters - stands in for sha256/UUID fragments. */
  hex(length: number): string;
}

export function createRng(seed: number): Rng {
  let state = seed >>> 0;

  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  const int = (min: number, max: number): number => min + Math.floor(next() * (max - min + 1));

  const pick = <T,>(items: readonly T[]): T => {
    if (items.length === 0) throw new Error('pick() on an empty list');
    return items[int(0, items.length - 1)] as T;
  };

  const shuffle = <T,>(items: readonly T[]): T[] => {
    const copy = items.slice();
    for (let i = copy.length - 1; i > 0; i -= 1) {
      const j = int(0, i);
      const a = copy[i] as T;
      const b = copy[j] as T;
      copy[i] = b;
      copy[j] = a;
    }
    return copy;
  };

  const sample = <T,>(items: readonly T[], count: number): T[] => {
    const chosen = new Set<number>();
    const limit = Math.min(count, items.length);
    while (chosen.size < limit) chosen.add(int(0, items.length - 1));
    return items.filter((_, index) => chosen.has(index));
  };

  const hex = (length: number): string => {
    let out = '';
    while (out.length < length) out += Math.floor(next() * 0x10000).toString(16).padStart(4, '0');
    return out.slice(0, length);
  };

  return { next, int, chance: (p) => next() < p, pick, sample, shuffle, hex };
}

/** A UUID-shaped string from the seeded stream, so event ids are stable across reloads. */
export function seededUuid(rng: Rng): string {
  return `${rng.hex(8)}-${rng.hex(4)}-4${rng.hex(3)}-a${rng.hex(3)}-${rng.hex(12)}`;
}

/** Zero-padded sequential id, e.g. `TX-000042`. */
export function sequentialId(prefix: string, index: number, width = 6): string {
  return `${prefix}${String(index).padStart(width, '0')}`;
}
