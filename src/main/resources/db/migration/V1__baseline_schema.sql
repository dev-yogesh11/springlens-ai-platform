-- ============================================================
-- V1__baseline_schema.sql
-- Baseline capture of existing Spring AI vector_store table.
-- This table was originally created by Spring AI initialize-schema.
-- Flyway takes over schema management from this point.
-- ============================================================

-- Enable pgvector extension if not already enabled
CREATE EXTENSION IF NOT EXISTS vector;

-- vector_store table — created by Spring AI, captured here as baseline
CREATE TABLE IF NOT EXISTS vector_store (
    id       UUID        NOT NULL,
    content  TEXT,
    metadata JSON,
    embedding vector(1536),
    CONSTRAINT vector_store_pkey PRIMARY KEY (id)
);

-- HNSW index for cosine similarity search — created by Spring AI
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store
    USING hnsw (embedding vector_cosine_ops);
