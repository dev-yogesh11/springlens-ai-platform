package com.ai.spring_lens.security;

import java.util.UUID;

/**
 * Immutable tenant context extracted from JWT on every request.
 *
 * Carried through the reactive chain via Reactor Context —
 * same concept as ThreadLocal in servlet stack but reactive-safe.
 *
 * Contains everything downstream services need to:
 * - Filter vector_store by tenantId (data isolation)
 * - Record userId in audit_events
 * - Check role for authorization decisions
 *
 * Java analogy: like a request-scoped bean in Spring MVC,
 * but stored in Reactor Context instead of ThreadLocal.
 */
public record TenantContext(
        UUID userId,
        UUID tenantId,
        String email,
        String role
) {

    /**
     * Reactor Context key — used to store and retrieve TenantContext.
     * String key keeps it simple — no extra class needed.
     */
    public static final String CONTEXT_KEY = "tenantContext";

    /**
     * Convenience method — checks if user has ADMIN role.
     */
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /**
     * Convenience method — checks if user has at least USER role.
     * ADMIN also passes this check.
     */
    public boolean canQuery() {
        return "ADMIN".equals(role) || "USER".equals(role);
    }
}