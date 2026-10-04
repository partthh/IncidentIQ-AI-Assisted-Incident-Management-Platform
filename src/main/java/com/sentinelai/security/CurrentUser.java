package com.sentinelai.security;

import com.sentinelai.common.ForbiddenActionException;
import com.sentinelai.common.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the authenticated caller from the security context.
 *
 * <p>Every mutating endpoint resolves the actor through here so the audit trail
 * records who did what, without each controller having to thread the principal
 * through its signature.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<SentinelPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SentinelPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public static SentinelPrincipal require() {
        return find().orElseThrow(() -> new ForbiddenActionException("Authentication required"));
    }

    public static UUID requireUserId() {
        return require().userId();
    }

    public static void requireCanWrite() {
        Role role = require().role();
        if (!role.canWrite()) {
            throw new ForbiddenActionException("Role " + role + " cannot modify incidents");
        }
    }

    public static void requireAdmin() {
        if (!require().role().isAdmin()) {
            throw new ForbiddenActionException("This action requires the ADMIN role");
        }
    }
}
