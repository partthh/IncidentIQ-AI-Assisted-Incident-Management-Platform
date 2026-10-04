package com.sentinelai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Hard limits applied at the ingestion edge, before anything touches the database. */
@ConfigurationProperties(prefix = "sentinel.ingestion")
public record IngestionProperties(

        @DefaultValue("65536") int maxPayloadBytes,

        @DefaultValue("4000") int maxMessageLength,

        @DefaultValue("40") int maxMetadataEntries,

        /** Queue an AI investigation automatically once an incident has enough evidence. */
        @DefaultValue("true") boolean autoInvestigate,

        /** Guard against a runaway producer opening unbounded incidents for one service. */
        @DefaultValue("25") int maxActiveIncidentsPerService
) {
}
