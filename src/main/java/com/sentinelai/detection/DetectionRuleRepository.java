package com.sentinelai.detection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectionRuleRepository extends JpaRepository<DetectionRuleEntity, UUID> {

    Optional<DetectionRuleEntity> findByCode(String code);

    List<DetectionRuleEntity> findByEnabledTrueOrderByCodeAsc();

    List<DetectionRuleEntity> findByEventTypeAndEnabledTrueOrderByCodeAsc(com.sentinelai.common.EventType eventType);
}
