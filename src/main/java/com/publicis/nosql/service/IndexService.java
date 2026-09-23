package com.publicis.nosql.service;

import com.publicis.nosql.domain.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Creates/drops indexes from the entity annotations on demand (auto-index-creation is OFF).
 * Explicit control = deterministic startup order (shard first, index second) and BEFORE/AFTER benchmarks.
 */
@Service
@RequiredArgsConstructor
public class IndexService {
    private static final Logger log = LoggerFactory.getLogger(IndexService.class);
    private static final List<Class<?>> ENTITIES = List.of(Order.class, DeviceTelemetry.class, CustomerLocation.class);

    private final MongoTemplate mongo;
    private final MongoMappingContext mappingContext;

    public void ensureAll() { ENTITIES.forEach(this::ensure); }

    /** Resolves @Indexed/@CompoundIndex/@TextIndexed/@GeoSpatialIndexed metadata and creates them. */
    public void ensure(Class<?> type) {
        IndexOperations ops = mongo.indexOps(type);
        for (IndexDefinition def : new MongoPersistentEntityIndexResolver(mappingContext).resolveIndexFor(type)) {
            ops.ensureIndex(def);
        }
        log.info("Indexes ensured for {}", type.getSimpleName());
    }

    /** Drops every secondary index (keeps _id and, on sharded collections, whatever MongoDB refuses to drop). */
    public int dropSecondary(Class<?> type) {
        IndexOperations ops = mongo.indexOps(type);
        int dropped = 0;
        for (IndexInfo info : ops.getIndexInfo()) {
            if ("_id_".equals(info.getName())) continue;
            try { ops.dropIndex(info.getName()); dropped++; }
            catch (Exception e) { log.warn("Could not drop {} (likely the shard-key index): {}", info.getName(), e.getMessage()); }
        }
        return dropped;
    }

    public List<Map<String, Object>> describe(Class<?> type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IndexInfo i : mongo.indexOps(type).getIndexInfo()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.getName());
            m.put("keys", i.getIndexFields().toString());
            m.put("unique", i.isUnique());
            m.put("sparse", i.isSparse());
            m.put("partialFilter", i.getPartialFilterExpression());
            m.put("ttlSeconds", i.getExpireAfter().map(Duration::getSeconds).orElse(null));
            out.add(m);
        }
        return out;
    }
}
