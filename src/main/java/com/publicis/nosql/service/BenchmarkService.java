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
