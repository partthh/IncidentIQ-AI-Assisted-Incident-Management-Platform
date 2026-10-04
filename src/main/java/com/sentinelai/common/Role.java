package com.sentinelai.common;

/**
 * Application roles. Ordered from most to least privileged so that
 * {@code >= ADMIN} checks mean "can administer".
 */
public enum Role {
    ADMIN,
    ENGINEER,
    VIEWER;

    public boolean canWrite() {
        return this == ADMIN || this == ENGINEER;
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }
}
