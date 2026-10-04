package com.sentinelai.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findAllByOrderByNameAsc();

    /**
     * Users who may own incidents. Used by the assignment endpoint so the UI offers
     * only people who can actually action what they are assigned, and by the
     * admin-only user list.
     */
    @Query("""
            select u from AppUser u
            where u.role in (com.sentinelai.common.Role.ADMIN, com.sentinelai.common.Role.ENGINEER)
            order by u.name asc
            """)
    List<AppUser> findAssignableOrderByNameAsc();
}
