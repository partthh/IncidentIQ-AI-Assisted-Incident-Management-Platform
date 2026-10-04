package com.sentinelai.events;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

    Optional<ServiceEntity> findByNameIgnoreCaseAndEnvironmentIgnoreCase(String name, String environment);

    boolean existsByNameIgnoreCaseAndEnvironmentIgnoreCase(String name, String environment);

    @Query("select s from ServiceEntity s where lower(s.name) = lower(:name) order by s.environment")
    java.util.List<ServiceEntity> findByName(@Param("name") String name);
}
