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
