package com.publicis.nosql.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("customer_locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CustomerLocation {
    @Id private String id;
    private String customerId;
    private String city;

    /** 2dsphere index: GeoJSON [longitude, latitude] (NOTE the order!) on a spherical (earth) model. */
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE, name = "geo_location")
    private GeoJsonPoint location;
}
