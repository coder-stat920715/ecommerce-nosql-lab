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
