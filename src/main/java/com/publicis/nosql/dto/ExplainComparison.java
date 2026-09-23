package com.publicis.nosql.dto;

public record ExplainComparison(
        String queryShape,
        ExplainMetrics collectionScan,   // forced with hint({$natural:1})
        ExplainMetrics indexScan,        // forced with the ESR / partial index
        ExplainMetrics plannerChoice,    // no hint: what the query planner picks
        String docsExaminedReduction) {}
