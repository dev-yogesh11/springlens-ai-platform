-- ============================================================
-- V4__add_auth_and_tenancy.sql
-- Adds JWT authentication, multi-tenancy, and audit logging.
--
-- What this migration does:
-- 1. Creates tenants table
-- 2. Creates users table with roles
-- 3. Adds tenant_id to vector_store + backfills 795 existing chunks
-- 4. Creates audit_events table
-- 5. Inserts one default tenant + one admin user for demo/interview
--
-- IDEMPOTENT: All statements use IF NOT EXISTS or DO blocks.
-- Safe to inspect manually via psql without Flyway.
-- ============================================================

-- ── 1. Tenants table ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT true,
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT tenants_pkey PRIMARY KEY (id),
    CONSTRAINT tenants_name_unique UNIQUE (name)
);

-- ── 2. Users table ───────────────────────────────────────────
-- role: ADMIN | USER | VIEWER
-- password_hash: BCrypt hashed — never plain text
-- tenant_id: FK to tenants — every user belongs to one tenant
CREATE TABLE IF NOT EXISTS users (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_tenant_fk
        FOREIGN KEY (tenant_id)
        REFERENCES tenants (id)
        ON DELETE RESTRICT,
    CONSTRAINT users_role_check
        CHECK (role IN ('ADMIN', 'USER', 'VIEWER'))
);

-- Index for login query — email lookup on every JWT validation
CREATE INDEX IF NOT EXISTS idx_users_email
    ON users (email);

-- Index for tenant user listing
CREATE INDEX IF NOT EXISTS idx_users_tenant_id
    ON users (tenant_id);

-- ── 3. Add tenant_id to vector_store ─────────────────────────
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

-- Index for tenant-scoped similarity search
-- Every query filters by tenant_id — this index is critical for performance
CREATE INDEX IF NOT EXISTS idx_vector_store_tenant_id
    ON vector_store (tenant_id);

-- ── 4. Audit events table ────────────────────────────────────
-- Records every query for cost tracking, compliance, and analytics
CREATE TABLE IF NOT EXISTS audit_events (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    query_hash     VARCHAR(64)  NOT NULL,
    retrieval_strategy VARCHAR(50),
    sources_cited  TEXT[],
    prompt_tokens  INTEGER      NOT NULL DEFAULT 0,
    completion_tokens INTEGER   NOT NULL DEFAULT 0,
    total_tokens   INTEGER      NOT NULL DEFAULT 0,
    cost_usd       NUMERIC(10,6),
    latency_ms     BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT audit_events_pkey PRIMARY KEY (id)
);

-- Index for tenant cost dashboard query
CREATE INDEX IF NOT EXISTS idx_audit_tenant_created
    ON audit_events (tenant_id, created_at DESC);

-- Index for user activity query
CREATE INDEX IF NOT EXISTS idx_audit_user_created
    ON audit_events (user_id, created_at DESC);

-- ── 5. Seed data — default tenant + admin user ───────────────
-- Fixed UUID so backfill and application config can reference it
-- Change this password immediately in any non-demo environment

-- Insert default tenant with fixed UUID
INSERT INTO tenants (id, name, enabled, created_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'springlens-default',
    true,
    now()
) ON CONFLICT (name) DO NOTHING;

-- Insert admin user assigned to default tenant
 -- BCrypt hash generated with cost factor 12
INSERT INTO users (id, tenant_id, email, password_hash, role, enabled, created_at)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'admin@springlens.com',
    '$2a$12$/nBpp7LwY20i/ZMgvjzm6eVA/iSApyOMmK7CFjPh6Pz1aJFTQjDvi',
    'ADMIN',
    true,
    now()
) ON CONFLICT (email) DO NOTHING;

-- Insert demo USER assigned to default tenant
-- Password: User@123
INSERT INTO users (id, tenant_id, email, password_hash, role, enabled, created_at)
VALUES (
    'c0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'user@springlens.com',
    '$2a$12$wIbGwWzot2U4FogkySoOBOwCyk8dq9GRdT3OJzTeU0gZsggcf.85e',
    'USER',
    true,
    now()
) ON CONFLICT (email) DO NOTHING;

-- ── 6. Backfill existing 795 chunks with default tenant ──────
-- All chunks ingested before auth was added belong to default tenant
-- This ensures existing documents are immediately queryable after migration
UPDATE vector_store
SET tenant_id = 'a0000000-0000-0000-0000-000000000001'
WHERE tenant_id IS NULL;

-- ── 7. Make tenant_id NOT NULL after backfill ─────────────────
-- Safe now — all existing rows have been backfilled
ALTER TABLE vector_store
    ALTER COLUMN tenant_id SET NOT NULL;

-- Add FK constraint from vector_store to tenants
ALTER TABLE vector_store
    DROP CONSTRAINT IF EXISTS vector_store_tenant_fk;
ALTER TABLE vector_store
    ADD CONSTRAINT vector_store_tenant_fk
        FOREIGN KEY (tenant_id)
        REFERENCES tenants (id)
        ON DELETE RESTRICT;
