package com.sentinelai.security;

import com.sentinelai.common.Role;
import java.util.UUID;

/**
 * Identity of an authenticated caller.
 *
 * <p>Deliberately a plain record rather than a Spring Security
 * {@code Authentication}: the same identity is needed on the STOMP side, where no
 * {@code Authentication} object exists. It implements {@link java.security.Principal}
 * only because the STOMP accessor stores the session user under that type.
 * Wrapping into an {@code Authentication} happens once, in
 * {@link SentinelAuthentication}.
 */
public record SentinelPrincipal(UUID userId, String email, String name, Role role)
        implements java.security.Principal {

    @Override
    public String getName() {
        return email;
    }

    public boolean canWrite() {
        return role.canWrite();
    }
}
