package com.sentinelai.events;

import com.sentinelai.common.HealthStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "services")
public class ServiceEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 40)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 20)
    private HealthStatus healthStatus;

    @Column(name = "owner_team", length = 120)
    private String ownerTeam;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ServiceEntity() {
        // for JPA
    }

    public ServiceEntity(UUID id, String name, String environment, HealthStatus healthStatus,
                         String ownerTeam, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.environment = environment;
        this.healthStatus = healthStatus;
        this.ownerTeam = ownerTeam;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEnvironment() {
        return environment;
    }

    public HealthStatus getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(HealthStatus healthStatus) {
        this.healthStatus = healthStatus;
    }

    public String getOwnerTeam() {
        return ownerTeam;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Stable identifier used in incident fingerprints and AI context. */
    public String getQualifiedName() {
        return environment + "/" + name;
    }
}
