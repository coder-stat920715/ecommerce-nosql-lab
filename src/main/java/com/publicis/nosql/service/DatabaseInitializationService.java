package com.publicis.nosql.service;

import com.publicis.nosql.domain.*;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Startup orchestration: (1) enableSharding on the DB, (2) shardCollection on EMPTY collections
 * (MongoDB auto-creates the shard-key index), (3) create the rest of the indexes.
 * Admin commands must run against the "admin" database through mongos.
 */
@Service
@RequiredArgsConstructor
public class DatabaseInitializationService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializationService.class);

    private final MongoTemplate mongo;
    private final IndexService indexService;
    @Value("${app.sharding.enabled:true}") private boolean shardingEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (shardingEnabled) {
            String db = mongo.getDb().getName();
            admin(new Document("enableSharding", db));

            // RANGE compound key: tenant-scoped queries are TARGETED (single shard) and range-friendly.
            shardCollection(db, "orders", new Document("tenantId", 1).append("customerId", 1), false);
            // HASHED key: even distribution for monotonically growing / write-heavy telemetry.
            shardCollection(db, "device_telemetry", new Document("deviceId", "hashed"), true);
            // HASHED customerId: uniform spread; geo queries scatter-gather anyway.
            shardCollection(db, "customer_locations", new Document("customerId", "hashed"), true);
        }
        indexService.ensureAll();
    }

    private void shardCollection(String db, String collection, Document key, boolean hashed) {
        Document cmd = new Document("shardCollection", db + "." + collection).append("key", key);
        // Pre-split hashed collections so writes hit both shards immediately instead of one initial chunk.
        if (hashed) cmd.append("numInitialChunks", 4);
        admin(cmd);
    }

    /** Idempotent: re-sharding with the same key is a no-op; other failures are logged, not fatal. */
    private void admin(Document command) {
        try {
            Document r = mongo.getMongoDatabaseFactory().getMongoDatabase("admin").runCommand(command);
            log.info("admin {} -> ok={}", command.toJson(), r.get("ok"));
        } catch (Exception e) {
            log.warn("admin {} failed: {}", command.toJson(), e.getMessage());
        }
    }
}
