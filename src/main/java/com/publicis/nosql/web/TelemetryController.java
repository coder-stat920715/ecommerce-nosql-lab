package com.publicis.nosql.web;

import com.publicis.nosql.domain.*;
import com.publicis.nosql.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {
    private final TelemetryService service;

    @PostMapping
    public DeviceTelemetry record(@RequestBody DeviceTelemetry t) { return service.record(t); }

    @GetMapping("/device/{deviceId}")
    public List<DeviceTelemetry> latest(@PathVariable String deviceId, @RequestParam(defaultValue = "20") int limit) {
        return service.latest(deviceId, limit);
    }

    /** Shows the TTL index (ttlSeconds). Docs vanish ~60s-granularity after recordedAt + ttl. */
    @GetMapping("/ttl-info")
    public List<Map<String, Object>> ttlInfo() { return service.ttlIndexInfo(); }

    @PostMapping("/locations")
    public CustomerLocation saveLocation(@RequestBody CustomerLocation l) { return service.saveLocation(l); }

    @GetMapping("/locations/near")
    public List<CustomerLocation> near(@RequestParam double lon, @RequestParam double lat,
                                       @RequestParam(defaultValue = "5") double maxKm) {
        return service.near(lon, lat, maxKm);
    }

    @GetMapping("/locations/within")
    public List<CustomerLocation> within(@RequestParam double lon, @RequestParam double lat,
                                         @RequestParam(defaultValue = "5") double radiusKm) {
        return service.within(lon, lat, radiusKm);
    }
}
