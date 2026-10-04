package com.sentinelai.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Adapts a {@link SentinelPrincipal} to Spring Security's {@code Authentication}. */
public final class SentinelAuthentication implements Authentication {

    private final SentinelPrincipal principal;
    private final boolean authenticated;

    /** Set by the servlet filter to the originating request, for audit logging. */
    private Object details;

    private SentinelAuthentication(SentinelPrincipal principal, boolean authenticated) {
        this.principal = principal;
        this.authenticated = authenticated;
    }

    public static SentinelAuthentication of(SentinelPrincipal principal) {
        return new SentinelAuthentication(principal, true);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
    }

    @Override
    public Object getCredentials() {
        // The JWT signature was already verified; there is nothing left to check.
        return null;
    }

    @Override
    public Object getDetails() {
        return details != null ? details : principal.email();
    }

    public void setDetails(Object details) {
        this.details = details;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) {
        throw new UnsupportedOperationException("Authentication state is fixed at construction");
    }

    @Override
    public String getName() {
        return principal.email();
    }

    @Override
    public String toString() {
        return principal.email() + " (" + principal.role() + ")";
    }
}
