-- ============================================================
-- V4__add_auth_and_tenancy.sql
-- Adds JWT authentication, multi-tenancy, and audit logging.
--
-- What this migration does:
-- 1. Creates tenants table
-- 2. Creates users table with roles
-- 3. Creates audit_events table
-- 4. Inserts one default tenant + demo users for demo/interview
--
-- NOTE: vector_store.tenant_id is intentionally NOT added here.
-- Spring AI's PGVectorStore issues a fixed 4-column INSERT:
--   INSERT INTO vector_store (id, content, metadata, embedding)
-- Any additional NOT NULL column causes a constraint violation.
-- tenant_id is stored inside the metadata jsonb column instead
-- (see V5) — Spring AI's filterExpression("tenant_id == 'x'")
-- resolves via jsonpath against metadata, not a separate column.
--
-- IDEMPOTENT: All statements use IF NOT EXISTS or ON CONFLICT.
-- ============================================================

-- ── 1. Tenants table ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT tenants_pkey        PRIMARY KEY (id),
    CONSTRAINT tenants_name_unique UNIQUE (name)
);

-- ── 2. Users table ───────────────────────────────────────────
-- role: ADMIN | USER | VIEWER
-- password_hash: BCrypt hashed — never plain text
-- tenant_id: FK to tenants — every user belongs to one tenant
CREATE TABLE IF NOT EXISTS users (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT users_pkey       PRIMARY KEY (id),
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

-- ── 3. Audit events table ────────────────────────────────────
-- Records every query for cost tracking, compliance, and analytics
CREATE TABLE IF NOT EXISTS audit_events (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL,
    user_id            UUID         NOT NULL,
    query_hash         VARCHAR(64)  NOT NULL,
    retrieval_strategy VARCHAR(50),
    sources_cited      TEXT[],
    prompt_tokens      INTEGER      NOT NULL DEFAULT 0,
    completion_tokens  INTEGER      NOT NULL DEFAULT 0,
    total_tokens       INTEGER      NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(10,6),
    latency_ms         BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT audit_events_pkey PRIMARY KEY (id)
);

-- Index for tenant cost dashboard query
CREATE INDEX IF NOT EXISTS idx_audit_tenant_created
    ON audit_events (tenant_id, created_at DESC);

-- Index for user activity query
CREATE INDEX IF NOT EXISTS idx_audit_user_created
    ON audit_events (user_id, created_at DESC);

-- ── 4. Seed data — default tenant + demo users ───────────────
-- Fixed UUIDs so downstream migrations and app config can reference them.
-- Change passwords immediately in any non-demo environment.

-- Default tenant
INSERT INTO tenants (id, name, enabled, created_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'springlens-default',
    true,
    now()
) ON CONFLICT (name) DO NOTHING;

-- Admin user — Password: Admin@123
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

-- Demo USER — Password: User@123
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