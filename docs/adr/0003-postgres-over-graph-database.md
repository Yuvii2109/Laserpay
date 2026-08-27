# ADR-0003 - PostgreSQL relationships instead of a graph database

**Status:** Accepted

## Context
The product speaks of an "evidence graph", which invites Neo4j. The reference doc explicitly
excludes it initially (section 33) under the rule that no technology enters without a workload.

## Decision
Model the graph as ordinary relational rows: `evidence_relationships(from_id, to_id, type)`
plus the natural foreign keys between transaction, payment, order, shipment, delivery, refund
and communication. `EvidenceGraphService` assembles nodes and edges per transaction.

## Consequences
- The queries we actually run are shallow: "everything attached to one transaction", "the
  version chain of one artifact". These are one to three joins, not traversals. Postgres wins.
- We give up cheap deep pattern matching across merchants (for example, finding fraud rings).
  That is explicitly not this product (reference doc section 2 - not a fraud detector).
- Revisit if a real workload appears that traverses more than three hops or matches
  variable-length paths. Until measured, this stands.
