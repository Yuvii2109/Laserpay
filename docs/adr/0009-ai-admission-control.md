# ADR-0009 - Admission control rations model invocations

**Status:** Accepted

## Context
Reference doc sections 4.6, 19 and 29: the AI layer must scale with ambiguity, not with data
volume, and the prototype must stay inside Gemini's free quota. Sending every case to a model
would be both expensive and wrong - most cases are not ambiguous.

## Decision
Before any investigation, `AdmissionController` computes a priority in [0, 100]:

```
0.40 * normalizedFinancialImpact
0.25 * deadlineUrgency          (1.0 when under 48h remain)
0.20 * ambiguityScore           (contradictions + gaps, normalized)
0.15 * (1 - deterministicConfidence)
```

Admit only when priority >= 55, the Redis token bucket allows it, and the daily budget
(`pdei:ai:budget:{date}`) is not exhausted. Three cases bypass the model entirely:
fully evidenced with zero contradictions (auto-prepare); zero evidence at all (accept
liability, routed to a human); already past deadline (escalate).

## Consequences
- Model spend tracks ambiguity, which grows far more slowly than event volume - the property
  the entire funnel argument rests on.
- The bypass rate is measured (`pdei_ai_admission_total{decision}` and `aiInvoked` on
  `CaseInvestigated`), so the AI-reduction claim is instrumented rather than asserted
  (reference doc section 37).
- A high-impact ambiguous case can still be starved by the budget. It escalates to a human
  rather than degrading silently, which is the correct failure direction where money is
  involved.
