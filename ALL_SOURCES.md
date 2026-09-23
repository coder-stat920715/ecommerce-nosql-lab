# Ecommerce NoSQL Lab: all sources

## README.md
```markdown
# E-Commerce NoSQL Indexing & Sharding Lab (Spring Boot 3 + MongoDB)

## Run
    docker compose up -d            # wait ~30s for cluster-init to finish
    mvn spring-boot:run             # app connects to mongos on :27017
    mvn verify                      # Testcontainers indexing test (single node)

## Demo script (header X-Tenant-Id defaults to tenantA)
    curl -X POST "localhost:8080/api/v1/benchmark/seed?count=100000"
    curl "localhost:8080/api/v1/performance/shard-distribution"
    curl "localhost:8080/api/v1/performance/explain?customerId=CUST-42&tenantId=tenantA"   # targeted, SINGLE_SHARD
    curl "localhost:8080/api/v1/performance/explain?customerId=CUST-42"                    # scatter-gather, SHARD_MERGE
    curl "localhost:8080/api/v1/performance/explain?scenario=partial"
    curl -X POST "localhost:8080/api/v1/benchmark/compare"                                 # BEFORE vs AFTER
    curl "localhost:8080/api/v1/orders/search?status=DELIVERED&customerId=CUST-42&useHint=true"
    curl "localhost:8080/api/v1/orders/text?q=fragile%20gift"
    curl "localhost:8080/api/v1/telemetry/ttl-info"
    curl -X POST localhost:8080/api/v1/telemetry/locations -H 'Content-Type: application/json' \
         -d '{"customerId":"CUST-1","city":"Pune","location":{"type":"Point","coordinates":[73.85,18.52]}}'
    curl "localhost:8080/api/v1/telemetry/locations/near?lon=73.85&lat=18.52&maxKm=10"
    curl -X POST localhost:8080/api/v1/tenants/orders -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' \
         -d '{"customerId":"C1","orderNumber":"A-1","status":"NEW","totalAmount":99}'

```

## docker-compose.yml
```yaml
# Sharded cluster: 1 config server, 2 shards, 1 mongos router. App connects ONLY to mongos (:27017).
services:
  configsvr:
    image: mongo:7.0
    command: mongod --configsvr --replSet cfgrs --port 27019 --bind_ip_all
  shard1:
    image: mongo:7.0
    command: mongod --shardsvr --replSet shard1rs --port 27018 --bind_ip_all
  shard2:
    image: mongo:7.0
    command: mongod --shardsvr --replSet shard2rs --port 27018 --bind_ip_all
  mongos:
    image: mongo:7.0
    command: mongos --configdb cfgrs/configsvr:27019 --bind_ip_all --port 27017
    ports: ["27017:27017"]
    depends_on: [configsvr, shard1, shard2]
    restart: on-failure   # mongos exits until the config replica set is initiated
  cluster-init:
    image: mongo:7.0
    depends_on: [mongos]
    volumes: ["./docker/init-cluster.sh:/init.sh:ro"]
    entrypoint: ["bash", "/init.sh"]

```

## pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>
  <groupId>com.publicis</groupId>
  <artifactId>ecommerce-nosql-lab</artifactId>
  <version>1.0.0</version>
  <properties><java.version>17</java.version></properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-mongodb</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>mongodb</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration><excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes></configuration>
      </plugin>
    </plugins>
  </build>
</project>

```

## docker/init-cluster.sh
```bash
#!/bin/bash
# Bootstraps replica sets and registers shards with mongos.
wait_for() { until mongosh --host "$1" --port "$2" --quiet --eval "db.adminCommand('ping')" >/dev/null 2>&1; do sleep 2; done; }
wait_for configsvr 27019; wait_for shard1 27018; wait_for shard2 27018
mongosh --host configsvr --port 27019 --quiet --eval 'rs.initiate({_id:"cfgrs",configsvr:true,members:[{_id:0,host:"configsvr:27019"}]})'
mongosh --host shard1 --port 27018 --quiet --eval 'rs.initiate({_id:"shard1rs",members:[{_id:0,host:"shard1:27018"}]})'
mongosh --host shard2 --port 27018 --quiet --eval 'rs.initiate({_id:"shard2rs",members:[{_id:0,host:"shard2:27018"}]})'
sleep 10; wait_for mongos 27017
mongosh --host mongos --port 27017 --quiet --eval 'sh.addShard("shard1rs/shard1:27018"); sh.addShard("shard2rs/shard2:27018"); sh.status()'

```

## src/main/java/com/publicis/nosql/EcommerceNoSqlLabApplication.java
```java
package com.publicis.nosql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EcommerceNoSqlLabApplication {
    public static void main(String[] args) { SpringApplication.run(EcommerceNoSqlLabApplication.class, args); }
}

```

## src/main/java/com/publicis/nosql/domain/CustomerLocation.java
```java
package com.publicis.nosql.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("customer_locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CustomerLocation {
    @Id private String id;
    private String customerId;
    private String city;

    /** 2dsphere index: GeoJSON [longitude, latitude] (NOTE the order!) on a spherical (earth) model. */
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE, name = "geo_location")
    private GeoJsonPoint location;
}

```

## src/main/java/com/publicis/nosql/domain/DeviceTelemetry.java
```java
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

```

## src/main/java/com/publicis/nosql/domain/Order.java
```java
package com.publicis.nosql.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

import java.time.Instant;
import java.util.List;

/**
 * Main high-volume collection. SHARD KEY = {tenantId, customerId} (range, compound):
 *  - tenantId gives coarse tenant isolation/locality, customerId adds cardinality (avoids jumbo chunks
 *    for one giant tenant). Neither is monotonically increasing, so no "hot shard" on inserts.
 *  - @Sharded lets Spring Data add the shard key to update/delete filters built from an entity.
 */
@Document(collection = "orders")
@Sharded(shardKey = {"tenantId", "customerId"})
@CompoundIndexes({
    /*
     * UNIQUENESS ON A SHARDED COLLECTION: MongoDB only enforces unique indexes whose keys start with the
     * shard key. A plain @Indexed(unique=true) on orderNumber would make shardCollection FAIL, so the unique
     * constraint is scoped to (tenantId, customerId, orderNumber). Cluster-wide global uniqueness needs a
     * generated key (ObjectId/UUID/Snowflake) or a separate lookup collection.
     */
    @CompoundIndex(name = Order.IDX_UNIQUE, def = "{'tenantId':1,'customerId':1,'orderNumber':1}", unique = true),

    /*
     * ESR RULE: Equality (status, customerId) -> Sort/Range (orderDate).
     * Serves: find({status, customerId}).sort({orderDate:-1}) and ranges on orderDate, no in-memory SORT stage.
     * Prefix rule: also serves {status} and {status, customerId}, but NOT {customerId} or {orderDate} alone.
     */
    @CompoundIndex(name = Order.IDX_ESR, def = "{'status':1,'customerId':1,'orderDate':-1}"),

    /*
     * PARTIAL INDEX: only indexes DELIVERED orders above 500 -> tiny index, cheaper writes.
     * The planner uses it ONLY if the query predicate is guaranteed to be inside the filter
     * (status = DELIVERED AND totalAmount > x, where x >= 500).
     */
    @CompoundIndex(name = Order.IDX_PARTIAL, def = "{'totalAmount':1}",
        partialFilter = "{'status':'DELIVERED','totalAmount':{'$gt':500}}")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {
    public static final String IDX_UNIQUE = "uq_tenant_customer_orderNumber";
    public static final String IDX_ESR = "esr_status_customer_date";
    public static final String IDX_PARTIAL = "partial_delivered_high_value";

    @Id private String id;
    private String tenantId;
    private String customerId;
    private String orderNumber;
    private String status;          // NEW, PAID, SHIPPED, DELIVERED, CANCELLED
    private double totalAmount;
    private Instant orderDate;

    /** SPARSE single-field index: only documents that HAVE couponCode are indexed (most orders don't). */
    @Indexed(sparse = true)
    private String couponCode;

    /** TEXT index (one per collection): multi-field, weighted. notes counts 3x more than tags. */
    @TextIndexed(weight = 3) private String notes;
    @TextIndexed private List<String> tags;
}

```

## src/main/java/com/publicis/nosql/dto/BenchmarkResult.java
```java
package com.publicis.nosql.dto;

public record BenchmarkResult(String label, int iterations, double avgMillis, double minMillis,
                              int returnedDocs, ExplainMetrics plan) {}

```

## src/main/java/com/publicis/nosql/dto/ExplainComparison.java
```java
package com.publicis.nosql.dto;

public record ExplainComparison(
        String queryShape,
        ExplainMetrics collectionScan,   // forced with hint({$natural:1})
        ExplainMetrics indexScan,        // forced with the ESR / partial index
        ExplainMetrics plannerChoice,    // no hint: what the query planner picks
        String docsExaminedReduction) {}

```

## src/main/java/com/publicis/nosql/dto/ExplainMetrics.java
```java
package com.publicis.nosql.dto;

/** Distilled explain("executionStats") output. */
public record ExplainMetrics(
        String label,
        String topStage,          // SINGLE_SHARD (targeted) / SHARD_MERGE (scatter-gather) / FETCH / ...
        String leafStage,         // IXSCAN vs COLLSCAN
        String indexName,         // null for COLLSCAN
        int shardsTargeted,
        long totalKeysExamined,
        long totalDocsExamined,
        long nReturned,
        long executionTimeMillis) {}

```

## src/main/java/com/publicis/nosql/service/BenchmarkService.java
```java
package com.publicis.nosql.service;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.dto.BenchmarkResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class BenchmarkService {
    private static final String[] TENANTS = {"tenantA", "tenantB", "tenantC"};
    private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"};
    private static final String[] WORDS = {"fragile", "gift", "express", "leave-at-door", "signature", "priority", "electronics", "fashion"};
    private static final int BATCH = 5_000;

    private final MongoTemplate mongo;
    private final IndexService indexService;
    private final QueryProfilerService profiler;

    /** Bulk insert synthetic orders in batches. Tip: seed BEFORE creating indexes for faster loads. */
    public Map<String, Object> seed(int count) {
        long start = System.nanoTime();
        String run = Long.toString(System.currentTimeMillis(), 36);   // keeps orderNumber unique across runs
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Order> batch = new ArrayList<>(BATCH);
        for (int i = 0; i < count; i++) {
            batch.add(Order.builder()
                    .tenantId(TENANTS[r.nextInt(TENANTS.length)])
                    .customerId("CUST-" + r.nextInt(5_000))                  // high cardinality shard-key component
                    .orderNumber("ORD-" + run + "-" + i)
                    .status(STATUSES[r.nextInt(STATUSES.length)])
                    .totalAmount(Math.round(r.nextDouble(10, 2_000) * 100) / 100.0)
                    .orderDate(Instant.now().minus(r.nextInt(365), ChronoUnit.DAYS).minusSeconds(r.nextInt(86_400)))
                    .couponCode(r.nextInt(10) == 0 ? "SAVE" + r.nextInt(50) : null)   // ~10% -> sparse index stays small
                    .notes(WORDS[r.nextInt(WORDS.length)] + " " + WORDS[r.nextInt(WORDS.length)] + " order")
                    .tags(List.of(WORDS[r.nextInt(WORDS.length)], WORDS[r.nextInt(WORDS.length)]))
                    .build());
            if (batch.size() == BATCH) { mongo.insert(batch, Order.class); batch.clear(); }
        }
        if (!batch.isEmpty()) mongo.insert(batch, Order.class);
        return Map.of("inserted", count, "seconds", (System.nanoTime() - start) / 1e9,
                "totalOrders", mongo.estimatedCount(Order.class));
    }

    /** Runs the ESR-shaped query N times and reports latency + the plan the planner chose. */
    public BenchmarkResult run(String label, String customerId, int iterations) {
        Query q = new Query(Criteria.where("status").is("DELIVERED").and("customerId").is(customerId))
                .with(Sort.by(Sort.Direction.DESC, "orderDate")).limit(50);
        double total = 0, min = Double.MAX_VALUE;
        int returned = 0;
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            returned = mongo.find(q, Order.class).size();
            double ms = (System.nanoTime() - t) / 1e6;
            total += ms; min = Math.min(min, ms);
        }
        return new BenchmarkResult(label, iterations, total / iterations, min, returned,
                profiler.explain(label, q, null));
    }

    /** One-shot BEFORE/AFTER: drop indexes -> benchmark -> create indexes -> benchmark. */
    public Map<String, Object> compare(String customerId, int iterations) {
        int dropped = indexService.dropSecondary(Order.class);
        BenchmarkResult before = run("BEFORE indexes", customerId, iterations);
        indexService.ensure(Order.class);
        BenchmarkResult after = run("AFTER indexes", customerId, iterations);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexesDropped", dropped);
        out.put("before", before);
        out.put("after", after);
        out.put("speedup", String.format("%.1fx", before.avgMillis() / Math.max(after.avgMillis(), 0.001)));
        return out;
    }
}

```

## src/main/java/com/publicis/nosql/service/DatabaseInitializationService.java
```java
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

```

## src/main/java/com/publicis/nosql/service/IndexService.java
```java
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

```

## src/main/java/com/publicis/nosql/service/OrderService.java
```java
package com.publicis.nosql.service;

import com.publicis.nosql.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final MongoTemplate mongo;

    public Order create(String tenantId, Order o) {
        o.setId(null);
        o.setTenantId(tenantId);
        if (o.getOrderDate() == null) o.setOrderDate(Instant.now());
        return mongo.insert(o);
    }

    /**
     * With customerId (full shard key = tenantId + customerId) mongos routes to ONE shard (targeted).
     * Without it the filter has no shard-key prefix -> scatter-gather across all shards.
     */
    public Order get(String tenantId, String id, String customerId) {
        Criteria c = Criteria.where("_id").is(id).and("tenantId").is(tenantId);
        if (customerId != null) c = c.and("customerId").is(customerId);
        return mongo.findOne(new Query(c), Order.class);
    }

    /** save() on a @Sharded entity adds the shard key to the update filter automatically. */
    public Order update(String tenantId, String id, Order o) {
        o.setId(id);
        o.setTenantId(tenantId);
        return mongo.save(o);
    }

    public long delete(String tenantId, String id, String customerId) {
        Query q = new Query(Criteria.where("_id").is(id).and("tenantId").is(tenantId).and("customerId").is(customerId));
        return mongo.remove(q, Order.class).getDeletedCount();
    }

    /** ESR-shaped search; useHint pins the compound index (withHint) - handy to prove which plan wins. */
    public List<Order> search(String tenantId, String status, String customerId, Instant from, Instant to, boolean useHint) {
        Criteria c = Criteria.where("tenantId").is(tenantId).and("status").is(status).and("customerId").is(customerId);
        if (from != null || to != null) {
            Criteria range = Criteria.where("orderDate");
            if (from != null) range = range.gte(Date.from(from));
            if (to != null) range = range.lte(Date.from(to));
            c = new Criteria().andOperator(c, range);
        }
        Query q = new Query(c).with(Sort.by(Sort.Direction.DESC, "orderDate")).limit(100);
        if (useHint) q.withHint(Order.IDX_ESR);
        return mongo.find(q, Order.class);
    }

    /** $text query ranked by relevance score using the weighted multi-field text index. */
    public List<Order> textSearch(String terms) {
        TextQuery q = TextQuery.queryText(TextCriteria.forDefaultLanguage().matchingAny(terms.split("\\s+")))
                .sortByScore().limit(20);
        return mongo.find(q, Order.class);
    }

    /** Uses the PARTIAL index only when minAmount >= 500 (query must be a subset of the index filter). */
    public List<Order> highValueDelivered(double minAmount) {
        Query q = new Query(Criteria.where("status").is("DELIVERED").and("totalAmount").gt(minAmount)).limit(50);
        return mongo.find(q, Order.class);
    }
}

```

## src/main/java/com/publicis/nosql/service/QueryProfilerService.java
```java
package com.publicis.nosql.service;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.FindIterable;
import com.publicis.nosql.domain.Order;
import com.publicis.nosql.dto.*;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Runs explain("executionStats") and distills the plan into interview-friendly metrics. */
@Service
@RequiredArgsConstructor
public class QueryProfilerService {
    private final MongoTemplate mongo;

    public ExplainComparison compare(String scenario, String status, String customerId, String tenantId) {
        Query q;
        String hintName;
        String shape;
        if ("partial".equalsIgnoreCase(scenario)) {
            q = new Query(Criteria.where("status").is("DELIVERED").and("totalAmount").gt(500));
            hintName = Order.IDX_PARTIAL;
            shape = "{status:'DELIVERED', totalAmount:{$gt:500}}";
        } else {
            Criteria c = Criteria.where("status").is(status).and("customerId").is(customerId);
            // Including the shard-key prefix (tenantId) makes the query TARGETED; omit it -> scatter-gather.
            if (tenantId != null && !tenantId.isBlank()) c = c.and("tenantId").is(tenantId);
            q = new Query(c).with(Sort.by(Sort.Direction.DESC, "orderDate"));
            hintName = Order.IDX_ESR;
            shape = q.getQueryObject().toJson() + " sort " + q.getSortObject().toJson();
        }
        ExplainMetrics scan = explain("COLLSCAN (hint $natural)", q, new Document("$natural", 1));
        ExplainMetrics idx = explain("INDEX (hint " + hintName + ")", q, hintName);
        ExplainMetrics auto = explain("PLANNER CHOICE (no hint)", q, null);
        String reduction = scan.totalDocsExamined() == 0 ? "n/a"
                : String.format("%.1f%% fewer docs examined (%d -> %d)",
                100.0 * (scan.totalDocsExamined() - idx.totalDocsExamined()) / scan.totalDocsExamined(),
                scan.totalDocsExamined(), idx.totalDocsExamined());
        return new ExplainComparison(shape, scan, idx, auto, reduction);
    }

    /** @param hint String = index name, Bson = key pattern (e.g. {$natural:1}), null = let the planner choose */
    public ExplainMetrics explain(String label, Query q, Object hint) {
        FindIterable<Document> it = mongo.getCollection(mongo.getCollectionName(Order.class)).find(q.getQueryObject());
        if (!q.getSortObject().isEmpty()) it = it.sort(q.getSortObject());
        if (hint instanceof String s) it = it.hintString(s);
        else if (hint instanceof Bson b) it = it.hint(b);

        Document explain = it.explain(ExplainVerbosity.EXECUTION_STATS);
        Document stats = explain.get("executionStats", Document.class);
        Document winning = explain.get("queryPlanner", Document.class).get("winningPlan", Document.class);
        // MongoDB 7 may wrap the plan in {queryPlan: {...}, slotBasedPlan: {...}}
        Document root = winning.containsKey("queryPlan") ? winning.get("queryPlan", Document.class) : winning;
        Optional<Document> leaf = findLeaf(winning);
        int shards = root.get("shards") instanceof List<?> l ? l.size() : 1;

        return new ExplainMetrics(label, root.getString("stage"),
                leaf.map(d -> d.getString("stage")).orElse("UNKNOWN"),
                leaf.map(d -> d.getString("indexName")).orElse(null),
                shards, num(stats, "totalKeysExamined"), num(stats, "totalDocsExamined"),
                num(stats, "nReturned"), num(stats, "executionTimeMillis"));
    }

    /** On a sharded cluster the IXSCAN/COLLSCAN sits under shards[].winningPlan..., so walk the tree. */
    private Optional<Document> findLeaf(Object node) {
        if (node instanceof Document d) {
            Object stage = d.get("stage");
            if ("IXSCAN".equals(stage) || "COLLSCAN".equals(stage)) return Optional.of(d);
            for (Object v : d.values()) { Optional<Document> r = findLeaf(v); if (r.isPresent()) return r; }
        } else if (node instanceof List<?> list) {
            for (Object v : list) { Optional<Document> r = findLeaf(v); if (r.isPresent()) return r; }
        }
        return Optional.empty();
    }

    private long num(Document d, String key) { return d.get(key) instanceof Number n ? n.longValue() : 0L; }
}

```

## src/main/java/com/publicis/nosql/service/TelemetryService.java
```java
package com.publicis.nosql.service;

import com.publicis.nosql.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TelemetryService {
    private final MongoTemplate mongo;
    private final IndexService indexService;

    public DeviceTelemetry record(DeviceTelemetry t) {
        t.setId(null);
        if (t.getRecordedAt() == null) t.setRecordedAt(Instant.now());   // TTL clock starts here
        return mongo.insert(t);
    }

    public List<DeviceTelemetry> latest(String deviceId, int limit) {
        return mongo.find(new Query(Criteria.where("deviceId").is(deviceId))
                .with(Sort.by(Sort.Direction.DESC, "recordedAt")).limit(limit), DeviceTelemetry.class);
    }

    public List<Map<String, Object>> ttlIndexInfo() { return indexService.describe(DeviceTelemetry.class); }

    public CustomerLocation saveLocation(CustomerLocation l) { l.setId(null); return mongo.insert(l); }

    /** $near: sorted nearest-first automatically, meters for GeoJSON. Requires the 2dsphere index. */
    public List<CustomerLocation> near(double lon, double lat, double maxKm) {
        Query q = new Query(Criteria.where("location").near(new GeoJsonPoint(lon, lat)).maxDistance(maxKm * 1000));
        return mongo.find(q, CustomerLocation.class);
    }

    /** $geoWithin: unsorted membership test inside a circle (no distance ordering, cheaper than $near). */
    public List<CustomerLocation> within(double lon, double lat, double radiusKm) {
        Query q = new Query(Criteria.where("location")
                .withinSphere(new Circle(new Point(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS))));
        return mongo.find(q, CustomerLocation.class);
    }
}

```

## src/main/java/com/publicis/nosql/tenant/TenantContext.java
```java
package com.publicis.nosql.tenant;

/** Per-request tenant holder (populated from the X-Tenant-Id header). */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TenantContext() {}
    public static void set(String t) { CURRENT.set(t); }
    public static String get() { String t = CURRENT.get(); return t == null ? "tenantA" : t; }
    public static void clear() { CURRENT.remove(); }
}

```

## src/main/java/com/publicis/nosql/tenant/TenantFilter.java
```java
package com.publicis.nosql.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String t = req.getHeader("X-Tenant-Id");
            TenantContext.set(t == null || t.isBlank() ? "tenantA" : t);
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();      // never leak tenant state across pooled threads
        }
    }
}

```

## src/main/java/com/publicis/nosql/tenant/TenantPartitionService.java
```java
package com.publicis.nosql.tenant;

import com.mongodb.client.MongoClient;
import com.publicis.nosql.domain.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * APPLICATION-LEVEL PARTITIONING (vs. DB-level sharding). Two isolation models:
 *  1) DATABASE-per-tenant  -> strongest isolation, per-tenant backup/restore/quotas, but many DBs/files.
 *  2) COLLECTION-per-tenant -> lighter, but thousands of collections/indexes hurt the WiredTiger cache.
 * Both share ONE MongoClient (connection pool); only the cheap MongoTemplate wrapper is per tenant.
 * Trade-off vs shared collection + tenantId shard key: less noisy-neighbour risk, more operational overhead.
 */
@Service
public class TenantPartitionService {
    private final MongoClient client;
    private final Map<String, MongoTemplate> templates = new ConcurrentHashMap<>();

    public TenantPartitionService(MongoClient client) { this.client = client; }

    public MongoTemplate templateFor(String tenantId) {
        String safe = sanitize(tenantId);
        return templates.computeIfAbsent(safe, t ->
                new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "ecommerce_" + t)));
    }

    public String collectionFor(String tenantId) { return "orders_" + sanitize(tenantId); }

    public Order saveInTenantDatabase(String tenantId, Order order) {
        order.setTenantId(tenantId);
        if (order.getOrderDate() == null) order.setOrderDate(Instant.now());
        return templateFor(tenantId).save(order, "orders");
    }

    public List<Order> listFromTenantDatabase(String tenantId, int limit) {
        return templateFor(tenantId).find(new Query().with(Sort.by(Sort.Direction.DESC, "orderDate")).limit(limit),
                Order.class, "orders");
    }

    public Order saveInTenantCollection(String tenantId, Order order) {
        order.setTenantId(tenantId);
        if (order.getOrderDate() == null) order.setOrderDate(Instant.now());
        return templateFor("shared").save(order, collectionFor(tenantId));
    }

    /** Tenant ids become DB/collection names: whitelist to block injection like "../admin". */
    private String sanitize(String t) {
        if (t == null || !t.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid tenantId");
        return t;
    }
}

```

## src/main/java/com/publicis/nosql/web/OrderController.java
```java
package com.publicis.nosql.web;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.service.OrderService;
import com.publicis.nosql.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService service;

    @PostMapping
    public Order create(@RequestBody Order order) { return service.create(TenantContext.get(), order); }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable String id, @RequestParam(required = false) String customerId) {
        Order o = service.get(TenantContext.get(), id, customerId);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @PutMapping("/{id}")
    public Order update(@PathVariable String id, @RequestBody Order order) {
        return service.update(TenantContext.get(), id, order);   // body must carry customerId (shard key)
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam String customerId) {
        return service.delete(TenantContext.get(), id, customerId) > 0
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/search")
    public List<Order> search(@RequestParam String status, @RequestParam String customerId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                              @RequestParam(defaultValue = "true") boolean useHint) {
        return service.search(TenantContext.get(), status, customerId, from, to, useHint);
    }

    @GetMapping("/text")
    public List<Order> text(@RequestParam String q) { return service.textSearch(q); }

    @GetMapping("/high-value")
    public List<Order> highValue(@RequestParam(defaultValue = "500") double minAmount) {
        return service.highValueDelivered(minAmount);
    }
}

```

## src/main/java/com/publicis/nosql/web/PerformanceController.java
```java
package com.publicis.nosql.web;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.dto.*;
import com.publicis.nosql.service.*;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
public class PerformanceController {
    private final QueryProfilerService profiler;
    private final BenchmarkService benchmark;
    private final IndexService indexService;
    private final MongoTemplate mongo;

    /** scenario=esr (default) | partial. Omit tenantId to see scatter-gather (SHARD_MERGE) vs targeted (SINGLE_SHARD). */
    @GetMapping("/api/v1/performance/explain")
    public ExplainComparison explain(@RequestParam(defaultValue = "esr") String scenario,
                                     @RequestParam(defaultValue = "DELIVERED") String status,
                                     @RequestParam(defaultValue = "CUST-42") String customerId,
                                     @RequestParam(required = false) String tenantId) {
        return profiler.compare(scenario, status, customerId, tenantId);
    }

    @GetMapping("/api/v1/performance/indexes")
    public List<Map<String, Object>> indexes() { return indexService.describe(Order.class); }

    /** Docs per shard: verifies the shard key spreads data (needs sharded cluster). */
    @GetMapping("/api/v1/performance/shard-distribution")
    public Map<String, Object> distribution() {
        Document stats = mongo.getDb().runCommand(new Document("collStats", "orders"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sharded", stats.getBoolean("sharded", false));
        out.put("totalCount", stats.get("count"));
        if (stats.get("shards") instanceof Document shards)
            shards.forEach((name, s) -> out.put(name, ((Document) s).get("count")));
        return out;
    }

    // ---- Benchmark flow: drop -> seed -> run (COLLSCAN) -> create -> run (IXSCAN), or /compare in one shot ----
    @PostMapping("/api/v1/benchmark/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "100000") int count) { return benchmark.seed(count); }

    @PostMapping("/api/v1/benchmark/indexes/drop")
    public Map<String, Object> drop() { return Map.of("dropped", indexService.dropSecondary(Order.class)); }

    @PostMapping("/api/v1/benchmark/indexes/create")
    public List<Map<String, Object>> create() { indexService.ensure(Order.class); return indexService.describe(Order.class); }

    @GetMapping("/api/v1/benchmark/run")
    public BenchmarkResult run(@RequestParam(defaultValue = "CUST-42") String customerId,
                               @RequestParam(defaultValue = "5") int iterations) {
        return benchmark.run("current state", customerId, iterations);
    }

    @PostMapping("/api/v1/benchmark/compare")
    public Map<String, Object> compare(@RequestParam(defaultValue = "CUST-42") String customerId,
                                       @RequestParam(defaultValue = "5") int iterations) {
        return benchmark.compare(customerId, iterations);
    }
}

```

## src/main/java/com/publicis/nosql/web/TelemetryController.java
```java
package com.publicis.nosql.web;

import com.publicis.nosql.domain.*;
import com.publicis.nosql.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {
    private final TelemetryService service;

    @PostMapping
    public DeviceTelemetry record(@RequestBody DeviceTelemetry t) { return service.record(t); }

    @GetMapping("/device/{deviceId}")
    public List<DeviceTelemetry> latest(@PathVariable String deviceId, @RequestParam(defaultValue = "20") int limit) {
        return service.latest(deviceId, limit);
    }

    /** Shows the TTL index (ttlSeconds). Docs vanish ~60s-granularity after recordedAt + ttl. */
    @GetMapping("/ttl-info")
    public List<Map<String, Object>> ttlInfo() { return service.ttlIndexInfo(); }

    @PostMapping("/locations")
    public CustomerLocation saveLocation(@RequestBody CustomerLocation l) { return service.saveLocation(l); }

    @GetMapping("/locations/near")
    public List<CustomerLocation> near(@RequestParam double lon, @RequestParam double lat,
                                       @RequestParam(defaultValue = "5") double maxKm) {
        return service.near(lon, lat, maxKm);
    }

    @GetMapping("/locations/within")
    public List<CustomerLocation> within(@RequestParam double lon, @RequestParam double lat,
                                         @RequestParam(defaultValue = "5") double radiusKm) {
        return service.within(lon, lat, radiusKm);
    }
}

```

## src/main/java/com/publicis/nosql/web/TenantController.java
```java
package com.publicis.nosql.web;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.tenant.TenantContext;
import com.publicis.nosql.tenant.TenantPartitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Demonstrates application-level routing: X-Tenant-Id header -> dedicated database / collection. */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {
    private final TenantPartitionService partitions;

    @PostMapping("/orders")
    public Order save(@RequestBody Order o) { return partitions.saveInTenantDatabase(TenantContext.get(), o); }

    @GetMapping("/orders")
    public List<Order> list(@RequestParam(defaultValue = "20") int limit) {
        return partitions.listFromTenantDatabase(TenantContext.get(), limit);
    }

    @PostMapping("/orders/collection-per-tenant")
    public Order saveCollection(@RequestBody Order o) { return partitions.saveInTenantCollection(TenantContext.get(), o); }
}

```

## src/main/resources/application.yml
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/ecommerce   # mongos router
      # We create indexes explicitly (IndexService) so we can drop/recreate them for BEFORE/AFTER benchmarks
      # and so shardCollection runs on an EMPTY, index-free collection first.
      auto-index-creation: false
app:
  sharding:
    enabled: true     # set false for single-node Mongo (e.g. the Testcontainers test)
logging.level.org.springframework.data.mongodb.core.MongoTemplate: INFO

```

## src/test/java/com/publicis/nosql/IndexingIT.java
```java
package com.publicis.nosql;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.dto.ExplainComparison;
import com.publicis.nosql.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers cannot easily start a full sharded cluster, so this test runs on a single node
 * (sharding disabled) and verifies the INDEXING half. Use docker-compose.yml for the sharding half.
 */
@Testcontainers
@SpringBootTest(properties = "app.sharding.enabled=false")
class IndexingIT {
    @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { r.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("ecommerce")); }

    @Autowired IndexService indexService;
    @Autowired BenchmarkService benchmark;
    @Autowired QueryProfilerService profiler;

    @Test
    void esrIndexBeatsCollectionScan() {
        benchmark.seed(5_000);
        assertThat(indexService.describe(Order.class)).anyMatch(m -> Order.IDX_ESR.equals(m.get("name")));

        ExplainComparison c = profiler.compare("esr", "DELIVERED", "CUST-42", "tenantA");
        assertThat(c.collectionScan().leafStage()).isEqualTo("COLLSCAN");
        assertThat(c.indexScan().leafStage()).isEqualTo("IXSCAN");
        assertThat(c.indexScan().indexName()).isEqualTo(Order.IDX_ESR);
        assertThat(c.indexScan().totalDocsExamined()).isLessThan(c.collectionScan().totalDocsExamined());
    }
}

```
