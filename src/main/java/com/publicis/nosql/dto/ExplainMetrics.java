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
