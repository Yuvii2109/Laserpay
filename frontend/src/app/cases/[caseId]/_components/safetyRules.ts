/**
 * The contract 9.3 validation gate, rendered as a checklist.
 *
 * `AiResultValidator` in `evidence-core` is the authority: it runs server-side, it is audited,
 * and its answer is the `SafetyVerdict` the gateway returns. This module re-evaluates the same
 * seven rules against the stored payload purely so the console can *show its working* - which
 * rule tripped, and on what value. Where the two disagree, the stored verdict wins and the UI
 * says so explicitly.
 *
 * Nothing here gates anything. It is an explanation, not a decision.
 */
import type { CaseXRay } from '@/lib/types/case';
import type { PolicyView } from '@/lib/types/policy';

export type RuleOutcome = 'pass' | 'fail' | 'not-applicable' | 'unknown';

export interface GateRule {
  /** Rule number exactly as contract 9.3 lists it. */
  ordinal: number;
  id: string;
  /** The rejection condition, quoted from the contract. */
  statement: string;
  /** Why the rule exists, in one line. */
  rationale: string;
  outcome: RuleOutcome;
  /** What this case's data looked like when the rule was applied. */
  observed: string;
}

export interface GateChecklist {
  rules: GateRule[];
  failed: GateRule[];
  /** True when at least one rule could not be evaluated (usually a missing policy). */
  incomplete: boolean;
}

const UNKNOWN_POLICY = 'No applicable policy was loaded, so this rule cannot be re-evaluated here.';

export function evaluateGateChecklist(xray: CaseXRay, policy: PolicyView | null): GateChecklist {
  const result = xray.investigation;
  const evidenceIds = new Set(xray.evidence.map((item) => item.evidenceId));
  const evidenceByType = new Map(xray.evidence.map((item) => [item.evidenceId, item.type]));
  const requirements = xray.readiness?.requirements ?? [];

  const rules: GateRule[] = [];

  /* 1 - every cited / supporting evidence id exists */
  if (!result) {
    rules.push({
      ordinal: 1,
      id: 'evidence-exists',
      statement: 'An evidenceId in supportingEvidence or citations does not exist.',
      rationale: 'An invented artifact is the single most dangerous failure mode of a reasoner.',
      outcome: 'not-applicable',
      observed: 'No investigation result exists for this case.',
      });
  } else {
    const referenced = new Set<string>([
      ...result.supportingEvidence,
      ...result.citations.map((citation) => citation.evidenceId),
    ]);
    const missing = [...referenced].filter((id) => !evidenceIds.has(id));
    rules.push({
      ordinal: 1,
      id: 'evidence-exists',
      statement: 'An evidenceId in supportingEvidence or citations does not exist.',
      rationale: 'An invented artifact is the single most dangerous failure mode of a reasoner.',
      outcome: missing.length === 0 ? 'pass' : 'fail',
      observed:
        missing.length === 0
          ? `${referenced.size} referenced id(s), all attached to this case.`
          : `${missing.length} referenced id(s) not attached to this case: ${missing.join(', ')}.`,
    });
  }

  /* 2 - every referenced artifact belongs to this case's transaction */
  const foreign = xray.evidence.filter((item) => item.transactionId !== xray.transactionId);
  rules.push({
    ordinal: 2,
    id: 'evidence-linked',
    statement: 'An evidence item is not linked to this case’s transaction.',
    rationale: 'Evidence from another transaction proves nothing about this one.',
    outcome: foreign.length === 0 ? 'pass' : 'fail',
    observed:
      foreign.length === 0
        ? `All ${xray.evidence.length} attached artifact(s) belong to ${xray.transactionId}.`
        : `${foreign.length} artifact(s) belong to a different transaction.`,
  });

  /* 3 - the recommended action is permitted by policy */
  if (!result) {
    rules.push({
      ordinal: 3,
      id: 'action-permitted',
      statement: 'recommendedAction is not permitted by the applicable policy.',
      rationale: 'The policy, not the model, decides which outcomes are even available.',
      outcome: 'not-applicable',
      observed: 'No investigation result exists for this case.',
    });
  } else if (!policy) {
    rules.push({
      ordinal: 3,
      id: 'action-permitted',
      statement: 'recommendedAction is not permitted by the applicable policy.',
      rationale: 'The policy, not the model, decides which outcomes are even available.',
      outcome: 'unknown',
      observed: UNKNOWN_POLICY,
    });
  } else {
    const permitted = policy.permittedActions.includes(result.recommendedAction);
    rules.push({
      ordinal: 3,
      id: 'action-permitted',
      statement: 'recommendedAction is not permitted by the applicable policy.',
      rationale: 'The policy, not the model, decides which outcomes are even available.',
      outcome: permitted ? 'pass' : 'fail',
      observed: `${result.recommendedAction} is ${permitted ? 'in' : 'not in'} the permitted set (${policy.permittedActions.join(', ')}).`,
    });
  }

  /* 4 - confidence floor for auto-prepare */
  const preparing = result?.recommendedAction === 'PREPARE_REPRESENTMENT';
  if (!result || !preparing) {
    rules.push({
      ordinal: 4,
      id: 'confidence-floor',
      statement: 'confidence < policy.autoPrepareMinConfidence while the action is PREPARE_REPRESENTMENT.',
      rationale: 'A low-confidence answer must never be turned into a filed representment.',
      outcome: 'not-applicable',
      observed: result
        ? `Action is ${result.recommendedAction}; the floor only binds PREPARE_REPRESENTMENT.`
        : 'No investigation result exists for this case.',
    });
  } else if (!policy) {
    rules.push({
      ordinal: 4,
      id: 'confidence-floor',
      statement: 'confidence < policy.autoPrepareMinConfidence while the action is PREPARE_REPRESENTMENT.',
      rationale: 'A low-confidence answer must never be turned into a filed representment.',
      outcome: 'unknown',
      observed: UNKNOWN_POLICY,
    });
  } else {
    const meets = result.confidence >= policy.autoPrepareMinConfidence;
    rules.push({
      ordinal: 4,
      id: 'confidence-floor',
      statement: 'confidence < policy.autoPrepareMinConfidence while the action is PREPARE_REPRESENTMENT.',
      rationale: 'A low-confidence answer must never be turned into a filed representment.',
      outcome: meets ? 'pass' : 'fail',
      observed: `confidence ${result.confidence.toFixed(3)} vs floor ${policy.autoPrepareMinConfidence.toFixed(3)}.`,
    });
  }

  /* 5 - contradiction ceiling for auto-prepare */
  if (!result || !preparing) {
    rules.push({
      ordinal: 5,
      id: 'contradiction-ceiling',
      statement: 'contradictions.length > policy.maxContradictions while the action is PREPARE_REPRESENTMENT.',
      rationale: 'Filing a representment built on self-contradicting evidence loses the dispute and the credibility.',
      outcome: 'not-applicable',
      observed: result
        ? `Action is ${result.recommendedAction}; the ceiling only binds PREPARE_REPRESENTMENT.`
        : 'No investigation result exists for this case.',
    });
  } else if (!policy) {
    rules.push({
      ordinal: 5,
      id: 'contradiction-ceiling',
      statement: 'contradictions.length > policy.maxContradictions while the action is PREPARE_REPRESENTMENT.',
      rationale: 'Filing a representment built on self-contradicting evidence loses the dispute and the credibility.',
      outcome: 'unknown',
      observed: UNKNOWN_POLICY,
    });
  } else {
    const count = result.contradictions.length;
    rules.push({
      ordinal: 5,
      id: 'contradiction-ceiling',
      statement: 'contradictions.length > policy.maxContradictions while the action is PREPARE_REPRESENTMENT.',
      rationale: 'Filing a representment built on self-contradicting evidence loses the dispute and the credibility.',
      outcome: count <= policy.maxContradictions ? 'pass' : 'fail',
      observed: `${count} contradiction(s) vs maximum ${policy.maxContradictions}.`,
    });
  }

  /* 6 - prohibited evidence types */
  if (!result) {
    rules.push({
      ordinal: 6,
      id: 'prohibited-types',
      statement: 'A prohibited evidence type appears in supportingEvidence.',
      rationale: 'Some artifacts may not be used at all - privacy, licensing or network rules.',
      outcome: 'not-applicable',
      observed: 'No investigation result exists for this case.',
    });
  } else if (!policy) {
    rules.push({
      ordinal: 6,
      id: 'prohibited-types',
      statement: 'A prohibited evidence type appears in supportingEvidence.',
      rationale: 'Some artifacts may not be used at all - privacy, licensing or network rules.',
      outcome: 'unknown',
      observed: UNKNOWN_POLICY,
    });
  } else {
    const prohibited = new Set(policy.prohibitedEvidenceTypes);
    const offenders = result.supportingEvidence.filter((id) => {
      const type = evidenceByType.get(id);
      return type !== undefined && prohibited.has(type);
    });
    rules.push({
      ordinal: 6,
      id: 'prohibited-types',
      statement: 'A prohibited evidence type appears in supportingEvidence.',
      rationale: 'Some artifacts may not be used at all - privacy, licensing or network rules.',
      outcome: offenders.length === 0 ? 'pass' : 'fail',
      observed:
        prohibited.size === 0
          ? 'This policy prohibits no evidence type.'
          : offenders.length === 0
            ? `Prohibited types (${[...prohibited].join(', ')}) are absent from supportingEvidence.`
            : `Prohibited artifact(s) cited: ${offenders.join(', ')}.`,
    });
  }

  /* 7 - DEFENDABLE with an unsatisfied mandatory requirement */
  const unsatisfiedMandatory = requirements.filter(
    (requirement) => requirement.strength === 'MANDATORY' && !requirement.satisfied,
  );
  if (!result) {
    rules.push({
      ordinal: 7,
      id: 'defendable-mandatory',
      statement: 'classification is DEFENDABLE while a MANDATORY requirement is unsatisfied.',
      rationale: 'A case cannot be defendable while the network’s own required proof is missing.',
      outcome: 'not-applicable',
      observed: 'No investigation result exists for this case.',
    });
  } else if (result.classification !== 'DEFENDABLE') {
    rules.push({
      ordinal: 7,
      id: 'defendable-mandatory',
      statement: 'classification is DEFENDABLE while a MANDATORY requirement is unsatisfied.',
      rationale: 'A case cannot be defendable while the network’s own required proof is missing.',
      outcome: 'not-applicable',
      observed: `Classification is ${result.classification}; the rule only binds DEFENDABLE.`,
    });
  } else {
    rules.push({
      ordinal: 7,
      id: 'defendable-mandatory',
      statement: 'classification is DEFENDABLE while a MANDATORY requirement is unsatisfied.',
      rationale: 'A case cannot be defendable while the network’s own required proof is missing.',
      outcome: unsatisfiedMandatory.length === 0 ? 'pass' : 'fail',
      observed:
        unsatisfiedMandatory.length === 0
          ? `All ${requirements.filter((r) => r.strength === 'MANDATORY').length} mandatory requirement(s) satisfied.`
          : `Unsatisfied: ${unsatisfiedMandatory.map((r) => r.type).join(', ')}.`,
    });
  }

  return {
    rules,
    failed: rules.filter((rule) => rule.outcome === 'fail'),
    incomplete: rules.some((rule) => rule.outcome === 'unknown'),
  };
}
