package com.ecabin.ledger.service;

import java.util.Map;
import com.github.benmanes.caffeine.cache.Cache;

/** Typed holder avoids Spring treating the cache map as a collection of beans. */
public record ReferenceCacheRegistry(Map<TenantReferenceCache.Resource, Cache<TenantReferenceCache.Key, Object>> caches) {}
