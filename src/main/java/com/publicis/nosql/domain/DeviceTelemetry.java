package com.publicis.nosql.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

import java.time.Instant;

/**
 * High-write IoT/tracking events. Sharded HASHED on deviceId (see DatabaseInitializationService):
 * even write distribution, at the cost of scatter-gather for range queries on deviceId.
 */
@Document("device_telemetry")
@Sharded(shardKey = {"deviceId"})
@CompoundIndex(name = "device_time", def = "{'deviceId':1,'recordedAt':-1}")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeviceTelemetry {
    @Id private String id;
    private String deviceId;
    private String orderId;
    private double temperature;
    private int batteryLevel;

    /** TTL INDEX: a background monitor (runs every ~60s) deletes docs 1 hour after recordedAt. Must be a Date field. */
    @Indexed(name = "ttl_recordedAt", expireAfterSeconds = 3600)
    private Instant recordedAt;
}
