package com.sentinelai.api;

import com.sentinelai.common.ConflictException;
import com.sentinelai.common.HealthStatus;
import com.sentinelai.common.NotFoundException;
import com.sentinelai.events.ServiceEntity;
import com.sentinelai.events.ServiceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registry of monitored services.
 *
 * <p>Registration is an explicit, idempotent operation rather than a side effect of
 * ingesting. Auto-creating a service from whatever name appears in a payload would let
 * any producer invent services — and typos would fragment one team's telemetry across
 * several dashboards rows, which is exactly the failure this registry exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceRepository services;

    public ServiceController(ServiceRepository services) {
        this.services = services;
    }

    @GetMapping
    public List<ServiceView> list(@RequestParam(required = false) String environment) {
        return services.findAll().stream()
                .filter(service -> environment == null || environment.isBlank()
                        || service.getEnvironment().equalsIgnoreCase(environment.trim()))
                .sorted(java.util.Comparator.comparing(ServiceEntity::getName)
                        .thenComparing(ServiceEntity::getEnvironment))
                .map(ServiceView::from)
                .toList();
    }

    @GetMapping("/{serviceId}")
    public ServiceView get(@PathVariable UUID serviceId) {
        return services.findById(serviceId)
                .map(ServiceView::from)
                .orElseThrow(() -> NotFoundException.of("Service", serviceId));
    }

    /**
     * Registers a service, or returns the existing one unchanged.
     *
     * <p>Deliberately idempotent rather than rejecting duplicates: two operators
     * registering the same service concurrently is a benign race, and failing one of
     * them teaches them that registration is unreliable. The 201/200 split still tells
     * the caller which happened.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    @Transactional
    public ResponseEntity<ServiceView> register(@Valid @RequestBody RegisterServiceRequest request) {
        String name = request.name().trim();
        String environment = request.environment().trim().toLowerCase(Locale.ROOT);

        ServiceEntity existing = services
                .findByNameIgnoreCaseAndEnvironmentIgnoreCase(name, environment).orElse(null);
        if (existing != null) {
            return ResponseEntity.ok(ServiceView.from(existing));
        }

        ServiceEntity created = new ServiceEntity(UUID.randomUUID(), name, environment,
                request.healthStatus() == null ? HealthStatus.UNKNOWN : request.healthStatus(),
                request.ownerTeam() == null ? null : request.ownerTeam().trim(), Instant.now());
        services.save(created);
        return ResponseEntity.status(HttpStatus.CREATED).body(ServiceView.from(created));
    }

    public record RegisterServiceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 40) String environment,
            @Size(max = 120) String ownerTeam,
            HealthStatus healthStatus
    ) {
    }

    public record ServiceView(
            UUID id,
            String name,
            String environment,
            String qualifiedName,
            HealthStatus healthStatus,
            String ownerTeam,
            Instant createdAt
    ) {
        static ServiceView from(ServiceEntity service) {
            return new ServiceView(service.getId(), service.getName(), service.getEnvironment(),
                    service.getQualifiedName(), service.getHealthStatus(), service.getOwnerTeam(),
                    service.getCreatedAt());
        }
    }
}