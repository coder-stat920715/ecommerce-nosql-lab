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
