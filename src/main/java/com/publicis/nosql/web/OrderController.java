package com.publicis.nosql.web;

import com.publicis.nosql.domain.Order;
import com.publicis.nosql.service.OrderService;
import com.publicis.nosql.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService service;

    @PostMapping
    public Order create(@RequestBody Order order) { return service.create(TenantContext.get(), order); }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable String id, @RequestParam(required = false) String customerId) {
        Order o = service.get(TenantContext.get(), id, customerId);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @PutMapping("/{id}")
    public Order update(@PathVariable String id, @RequestBody Order order) {
        return service.update(TenantContext.get(), id, order);   // body must carry customerId (shard key)
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam String customerId) {
        return service.delete(TenantContext.get(), id, customerId) > 0
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/search")
    public List<Order> search(@RequestParam String status, @RequestParam String customerId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                              @RequestParam(defaultValue = "true") boolean useHint) {
        return service.search(TenantContext.get(), status, customerId, from, to, useHint);
    }

    @GetMapping("/text")
    public List<Order> text(@RequestParam String q) { return service.textSearch(q); }

    @GetMapping("/high-value")
    public List<Order> highValue(@RequestParam(defaultValue = "500") double minAmount) {
        return service.highValueDelivered(minAmount);
    }
}
