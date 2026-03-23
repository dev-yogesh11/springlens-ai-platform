-- ============================================================
-- V6__add_budget_and_rate_limits.sql
-- Adds budget enforcement and rate limiting columns to
-- tenants and users tables.
--
-- Three controls per tenant and per user:
-- 1. daily_request_limit   — max requests per day (429 if exceeded)
-- 2. daily_token_budget    — max tokens per day (429 if exceeded)
-- 3. monthly_token_budget  — max tokens per month (402 if exceeded)
--
-- NULL = unlimited — admin users and premium tenants have no cap.
-- User limits must be <= tenant limits (enforced at application layer).
--
-- Check order on every request:
-- 1. user daily_request_limit
-- 2. tenant daily_request_limit
-- 3. user daily_token_budget
-- 4. tenant daily_token_budget
-- 5. user monthly_token_budget
-- 6. tenant monthly_token_budget
--
-- IDEMPOTENT: All statements use IF NOT EXISTS / DO blocks.
-- ============================================================

-- ── Tenant-level budget columns ──────────────────────────────
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS monthly_token_budget  BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS daily_token_budget    BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS daily_request_limit   INTEGER DEFAULT NULL;

-- ── User-level budget columns ─────────────────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS monthly_token_budget  BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS daily_token_budget    BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS daily_request_limit   INTEGER DEFAULT NULL;

-- ── Seed sensible defaults for existing demo users ───────────
-- Default tenant — generous limits for demo/interview
UPDATE tenants
SET monthly_token_budget = 1000000,
    daily_token_budget   = 50000,
    daily_request_limit  = 500
WHERE id = 'a0000000-0000-0000-0000-000000000001';

-- Admin user — higher limits
UPDATE users
SET monthly_token_budget = 500000,
    daily_token_budget   = 25000,
    daily_request_limit  = 200
WHERE id = 'b0000000-0000-0000-0000-000000000001';

-- Demo USER — standard limits
UPDATE users
SET monthly_token_budget = 200000,
    daily_token_budget   = 10000,
    daily_request_limit  = 100
WHERE id = 'c0000000-0000-0000-0000-000000000001';
