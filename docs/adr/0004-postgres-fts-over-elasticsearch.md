# ADR-0004 - PostgreSQL full-text search instead of Elasticsearch

**Status:** Accepted

## Context
Evidence documents must be searchable by content. Reference doc sections 24 and 33 defer a
dedicated search cluster until benchmarks demonstrate need.

## Decision
`tsvector` columns on the evidence table, maintained by trigger, with GIN indexes and
`websearch_to_tsquery` for user-facing queries. Extracted document text is written by
`document-processor-service` into the indexed column.

## Consequences
- One less stateful system to run, back up, and keep consistent with Postgres.
- Search scope is per-merchant and usually filtered by transaction first, so the index stays
  small and selective.
- We forgo relevance tuning, fuzzy matching, and search-time aggregations. If a benchmark
  shows FTS latency or recall failing a real query pattern, this ADR is superseded - with the
  benchmark attached.
