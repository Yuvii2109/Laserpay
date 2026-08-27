# ADR-0006 - Readiness scoring is deterministic and model-free

**Status:** Accepted

## Context
Evidence Readiness is the number a merchant sees and acts on. If it moved because a model felt
differently today, it would be useless for planning and indefensible in review.

## Decision
`ReadinessEngine` implements the closed-form formula in `PLATFORM-CONTRACT.md` section 7:
weighted requirement satisfaction, four explicit penalty rules, clamp, round half-up, band
derivation. It is a pure function of (evidence set, requirement set, policy version, clock).
No model input, ever.

The UI renders the breakdown - every requirement, its strength, whether it is satisfied, and
each penalty applied with its point cost - so the score is fully explainable to a human.

## Consequences
- The same inputs always produce the same integer, which makes readiness testable with a table
  of cases and makes regressions visible.
- Scores are comparable across merchants and over time.
- The formula is a heuristic and its weights are a policy choice, not a truth. They live in
  policy versions, so changing them is an auditable, versioned act rather than a code edit.
