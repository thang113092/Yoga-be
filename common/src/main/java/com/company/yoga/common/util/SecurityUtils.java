package com.company.yoga.common.util;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private SecurityUtils() {}

    public static Optional<UUID> getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(auth.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static UUID getCurrentUserIdOrSystem() {
        return getCurrentUserId().orElse(SYSTEM_USER_ID);
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String cleanRole = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(cleanRole) || a.getAuthority().equalsIgnoreCase(role));
    }

    public static boolean isSuperAdmin() {
        return hasRole("SUPER_ADMIN");
    }

    public static boolean isBranchManager() {
        return hasRole("BRANCH_MANAGER");
    }
}
