package com.publicis.nosql.dto;

public record BenchmarkResult(String label, int iterations, double avgMillis, double minMillis,
                              int returnedDocs, ExplainMetrics plan) {}
