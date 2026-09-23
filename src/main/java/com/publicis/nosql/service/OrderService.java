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
        // FIX: Query.limit() returns Query (not TextQuery), so call it as a separate statement.
        TextQuery q = TextQuery.queryText(TextCriteria.forDefaultLanguage().matchingAny(terms.split("\\s+")))
                .sortByScore();
        q.limit(20);
        return mongo.find(q, Order.class);
    }

    /** Uses the PARTIAL index only when minAmount >= 500 (query must be a subset of the index filter). */
    public List<Order> highValueDelivered(double minAmount) {
        Query q = new Query(Criteria.where("status").is("DELIVERED").and("totalAmount").gt(minAmount)).limit(50);
        return mongo.find(q, Order.class);
    }
}