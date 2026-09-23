package com.publicis.nosql.web;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.tenant.TenantContext;
import com.publicis.nosql.tenant.TenantPartitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Demonstrates application-level routing: X-Tenant-Id header -> dedicated database / collection. */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {
    private final TenantPartitionService partitions;

    @PostMapping("/orders")
    public Order save(@RequestBody Order o) { return partitions.saveInTenantDatabase(TenantContext.get(), o); }

    @GetMapping("/orders")
    public List<Order> list(@RequestParam(defaultValue = "20") int limit) {
        return partitions.listFromTenantDatabase(TenantContext.get(), limit);
    }

    @PostMapping("/orders/collection-per-tenant")
    public Order saveCollection(@RequestBody Order o) { return partitions.saveInTenantCollection(TenantContext.get(), o); }
}
