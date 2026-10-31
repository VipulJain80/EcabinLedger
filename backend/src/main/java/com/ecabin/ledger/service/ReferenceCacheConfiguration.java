package com.ecabin.ledger.service;

import java.util.EnumMap;
import java.util.Map;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.binder.MeterBinder;

@Configuration
@EnableConfigurationProperties(ReferenceCacheProperties.class)
public class ReferenceCacheConfiguration {
    @Bean
    ReferenceCacheRegistry referenceCacheRegistry(ReferenceCacheProperties properties) {
        Map<TenantReferenceCache.Resource, Cache<TenantReferenceCache.Key, Object>> caches = new EnumMap<>(TenantReferenceCache.Resource.class);
        for (TenantReferenceCache.Resource resource : TenantReferenceCache.Resource.values()) {
            long maximum = resource == TenantReferenceCache.Resource.FORM_OPTIONS ? properties.getMaximumOptionEntries() : properties.getMaximumEntries();
            caches.put(resource, Caffeine.newBuilder().maximumSize(maximum).expireAfterWrite(resource.ttl(properties)).recordStats().build());
        }
        return new ReferenceCacheRegistry(caches);
    }

    @Bean
    MeterBinder referenceCacheMetrics(ReferenceCacheRegistry registry) {
        return meterRegistry -> registry.caches().forEach((resource, cache) -> {
            String name=resource.name().toLowerCase(java.util.Locale.ROOT);
            io.micrometer.core.instrument.Gauge.builder("ecabin.reference.cache.hits",cache,value->value.stats().hitCount()).tag("cache",name).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("ecabin.reference.cache.misses",cache,value->value.stats().missCount()).tag("cache",name).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("ecabin.reference.cache.evictions",cache,value->value.stats().evictionCount()).tag("cache",name).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("ecabin.reference.cache.load.failures",cache,value->value.stats().loadFailureCount()).tag("cache",name).register(meterRegistry);
        });
    }
}
