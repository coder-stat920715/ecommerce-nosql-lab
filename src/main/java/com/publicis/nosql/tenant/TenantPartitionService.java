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
