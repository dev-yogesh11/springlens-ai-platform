-- ============================================================
-- V2__add_hybrid_search.sql
-- Adds full-text search capability to vector_store table.
-- Enables hybrid search: vector similarity + BM25 keyword search
-- merged via Reciprocal Rank Fusion.
--
-- What this migration does:
-- 1. Adds fts_content tsvector column to vector_store
-- 2. Creates GIN index on fts_content for fast full-text search
-- 3. Creates trigger to auto-populate fts_content on INSERT/UPDATE
-- 4. Backfills fts_content for all 479 existing chunks
-- ============================================================

-- Step 1: Add tsvector column for full-text search
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS fts_content tsvector;

-- Step 2: Create GIN index for fast full-text search
-- GIN is correct for tsvector — do not use btree or HNSW here
CREATE INDEX IF NOT EXISTS idx_vector_store_fts
    ON vector_store
    USING gin(fts_content);

-- Step 3: Create trigger function to auto-populate fts_content
-- Uses 'english' dictionary for stemming and stop-word removal
-- to_tsvector converts plain text to searchable lexemes
CREATE OR REPLACE FUNCTION vector_store_fts_update()
    RETURNS trigger AS $$
BEGIN
    NEW.fts_content := to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Step 4: Attach trigger to vector_store — fires on INSERT and UPDATE
-- BEFORE trigger ensures fts_content is populated before row is written
DROP TRIGGER IF EXISTS vector_store_fts_trigger ON vector_store;
CREATE TRIGGER vector_store_fts_trigger
    BEFORE INSERT OR UPDATE OF content
    ON vector_store
    FOR EACH ROW
EXECUTE FUNCTION vector_store_fts_update();

-- Step 5: Backfill fts_content for all existing 479 chunks
-- This runs once at migration time — new rows handled by trigger
UPDATE vector_store
SET fts_content = to_tsvector('english', COALESCE(content, ''))
WHERE fts_content IS NULL;
