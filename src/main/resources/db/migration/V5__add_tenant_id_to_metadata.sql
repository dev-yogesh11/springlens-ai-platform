-- ============================================================
-- V5__add_tenant_id_to_metadata.sql
-- Ensures tenant_id is queryable via Spring AI filterExpression.
--
-- HOW SPRING AI TENANT ISOLATION WORKS:
--   - DocumentIngestionService sets doc.getMetadata().put("tenant_id", ...)
--   - PGVectorStore writes that into the metadata jsonb column automatically
--   - similaritySearch(filterExpression("tenant_id == 'uuid'")) translates
--     to a jsonpath predicate against metadata — no separate column needed
--
-- WHAT THIS MIGRATION DOES:
--   1. Adds a GIN index on metadata->>'tenant_id' for fast per-tenant
--      similarity search filtering (critical for multi-tenant performance)
--   2. No backfill needed — vector_store is empty at this point;
--      all future rows will have tenant_id in metadata at write time
--
-- IDEMPOTENT: CREATE INDEX IF NOT EXISTS is safe to re-run.
-- ============================================================

-- GIN index on the tenant_id key inside the metadata jsonb column.
-- Spring AI's filter expression engine targets metadata via jsonpath,
-- so this index is hit on every tenant-scoped similarity search.
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_tenant_id
    ON vector_store
    USING GIN ((metadata::jsonb));