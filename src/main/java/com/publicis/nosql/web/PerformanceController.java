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
