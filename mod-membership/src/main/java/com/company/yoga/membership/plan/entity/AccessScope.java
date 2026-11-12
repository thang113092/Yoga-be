package com.company.yoga.membership.plan.entity;

import java.util.Locale;
import java.util.Set;

/**
 * Where a plan's passes can be used (ONLINE_CLASS_PLAN.md, phase 2).
 * STUDIO: studio classes and online live classes. ONLINE: online live classes only (and videos later).
 * HYBRID: everything.
 */
public final class AccessScope {

    public static final String STUDIO = "STUDIO";
    public static final String ONLINE = "ONLINE";
    public static final String HYBRID = "HYBRID";
    public static final Set<String> ALL = Set.of(STUDIO, ONLINE, HYBRID);

    private AccessScope() {}

    /** Null or blank means STUDIO; unknown values return null. */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return STUDIO;
        }
        String scope = value.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(scope) ? scope : null;
    }

    /** Whether a pass with this scope can be used for a class with this delivery mode. */
    public static boolean allowsClass(String scope, String deliveryMode) {
        return !ONLINE.equals(scope) || "ONLINE".equals(deliveryMode);
    }
}
