package com.publicis.nosql.service;

import com.publicis.nosql.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TelemetryService {
    private final MongoTemplate mongo;
    private final IndexService indexService;

    public DeviceTelemetry record(DeviceTelemetry t) {
        t.setId(null);
        if (t.getRecordedAt() == null) t.setRecordedAt(Instant.now());   // TTL clock starts here
        return mongo.insert(t);
    }

    public List<DeviceTelemetry> latest(String deviceId, int limit) {
        return mongo.find(new Query(Criteria.where("deviceId").is(deviceId))
                .with(Sort.by(Sort.Direction.DESC, "recordedAt")).limit(limit), DeviceTelemetry.class);
    }

    public List<Map<String, Object>> ttlIndexInfo() { return indexService.describe(DeviceTelemetry.class); }

    public CustomerLocation saveLocation(CustomerLocation l) { l.setId(null); return mongo.insert(l); }

    /** $near: sorted nearest-first automatically, meters for GeoJSON. Requires the 2dsphere index. */
    public List<CustomerLocation> near(double lon, double lat, double maxKm) {
        Query q = new Query(Criteria.where("location").near(new GeoJsonPoint(lon, lat)).maxDistance(maxKm * 1000));
        return mongo.find(q, CustomerLocation.class);
    }

    /** $geoWithin: unsorted membership test inside a circle (no distance ordering, cheaper than $near). */
    public List<CustomerLocation> within(double lon, double lat, double radiusKm) {
        Query q = new Query(Criteria.where("location")
                .withinSphere(new Circle(new Point(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS))));
        return mongo.find(q, CustomerLocation.class);
    }
}
