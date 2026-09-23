# E-Commerce NoSQL Indexing & Sharding Lab (Spring Boot 3 + MongoDB)

## Run
    docker compose up -d            # wait ~30s for cluster-init to finish
    mvn spring-boot:run             # app connects to mongos on :27017
    mvn verify                      # Testcontainers indexing test (single node)

## Demo script (header X-Tenant-Id defaults to tenantA)
    curl -X POST "localhost:8080/api/v1/benchmark/seed?count=100000"
    curl "localhost:8080/api/v1/performance/shard-distribution"
    curl "localhost:8080/api/v1/performance/explain?customerId=CUST-42&tenantId=tenantA"   # targeted, SINGLE_SHARD
    curl "localhost:8080/api/v1/performance/explain?customerId=CUST-42"                    # scatter-gather, SHARD_MERGE
    curl "localhost:8080/api/v1/performance/explain?scenario=partial"
    curl -X POST "localhost:8080/api/v1/benchmark/compare"                                 # BEFORE vs AFTER
    curl "localhost:8080/api/v1/orders/search?status=DELIVERED&customerId=CUST-42&useHint=true"
    curl "localhost:8080/api/v1/orders/text?q=fragile%20gift"
    curl "localhost:8080/api/v1/telemetry/ttl-info"
    curl -X POST localhost:8080/api/v1/telemetry/locations -H 'Content-Type: application/json' \
         -d '{"customerId":"CUST-1","city":"Pune","location":{"type":"Point","coordinates":[73.85,18.52]}}'
    curl "localhost:8080/api/v1/telemetry/locations/near?lon=73.85&lat=18.52&maxKm=10"
    curl -X POST localhost:8080/api/v1/tenants/orders -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' \
         -d '{"customerId":"C1","orderNumber":"A-1","status":"NEW","totalAmount":99}'
