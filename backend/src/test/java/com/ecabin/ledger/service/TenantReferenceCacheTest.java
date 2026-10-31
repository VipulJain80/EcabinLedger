package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantReferenceCacheTest {
    private TenantReferenceCache cache;

    private TenantReferenceCache cache() {
        ReferenceCacheProperties properties = new ReferenceCacheProperties();
        Map<TenantReferenceCache.Resource, Cache<TenantReferenceCache.Key, Object>> caches = new EnumMap<>(TenantReferenceCache.Resource.class);
        for (TenantReferenceCache.Resource resource : TenantReferenceCache.Resource.values()) {
            caches.put(resource, Caffeine.newBuilder().maximumSize(16).expireAfterWrite(Duration.ofMinutes(1)).recordStats().build());
        }
        cache = new TenantReferenceCache(new ReferenceCacheRegistry(caches));
        return cache;
    }

    @AfterEach void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        if (TransactionSynchronizationManager.hasResource(TenantReferenceCache.class)) TransactionSynchronizationManager.unbindResource(TenantReferenceCache.class);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void cachesPerTenantAndReloadsAfterInvalidation() {
        TenantReferenceCache subject = cache();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        AtomicInteger firstLoads = new AtomicInteger(), secondLoads = new AtomicInteger();
        assertEquals("first", subject.get(TenantReferenceCache.Resource.AREAS, first, "active", () -> { firstLoads.incrementAndGet(); return "first"; }));
        subject.get(TenantReferenceCache.Resource.AREAS, first, "active", () -> { firstLoads.incrementAndGet(); return "wrong"; });
        assertEquals("second", subject.get(TenantReferenceCache.Resource.AREAS, second, "active", () -> { secondLoads.incrementAndGet(); return "second"; }));
        assertEquals(1, firstLoads.get());
        assertEquals(1, secondLoads.get());
        subject.invalidateAfterCommit(first, TenantReferenceCache.Resource.AREAS);
        assertEquals("first-new", subject.get(TenantReferenceCache.Resource.AREAS, first, "active", () -> "first-new"));
        assertEquals("second", subject.get(TenantReferenceCache.Resource.AREAS, second, "active", () -> "wrong"));
    }

    @Test void failedForcedRefreshKeepsLastGoodValue() {
        TenantReferenceCache subject = cache();
        UUID tenant = UUID.randomUUID();
        assertEquals("good", subject.get(TenantReferenceCache.Resource.CATEGORIES, tenant, "active", () -> "good"));
        assertThrows(IllegalStateException.class, () -> subject.refresh(TenantReferenceCache.Resource.CATEGORIES, tenant, "active", () -> { throw new IllegalStateException("loader failed"); }));
        assertEquals("good", subject.get(TenantReferenceCache.Resource.CATEGORIES, tenant, "active", () -> "unexpected"));
    }

    @Test void transactionInvalidationRunsOnlyAfterCommit() {
        TenantReferenceCache subject = cache();
        UUID tenant = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        subject.get(TenantReferenceCache.Resource.FLEETS, tenant, "active", () -> "old-" + loads.incrementAndGet());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        subject.invalidateAfterCommit(tenant, TenantReferenceCache.Resource.FLEETS);
        subject.invalidateAfterCommit(tenant, TenantReferenceCache.Resource.FLEETS);
        assertEquals("old-1", subject.get(TenantReferenceCache.Resource.FLEETS, tenant, "active", () -> "unexpected"));
        assertEquals(1, loads.get());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals("old-2", subject.get(TenantReferenceCache.Resource.FLEETS, tenant, "active", () -> "old-" + loads.incrementAndGet()));
        assertEquals(2, loads.get());
    }

    @Test void rollbackCompletionDoesNotInvalidate() {
        TenantReferenceCache subject = cache();
        UUID tenant = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        subject.get(TenantReferenceCache.Resource.COMPONENTS, tenant, "active", () -> "value-" + loads.incrementAndGet());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        subject.invalidateAfterCommit(tenant, TenantReferenceCache.Resource.COMPONENTS);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertEquals("value-1", subject.get(TenantReferenceCache.Resource.COMPONENTS, tenant, "active", () -> "value-" + loads.incrementAndGet()));
        assertEquals(1, loads.get());
    }

    @Test void invalidationWaitsForAnInFlightLoadAndRemovesItsValue() throws Exception {
        TenantReferenceCache subject = cache();
        UUID tenant = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> subject.get(TenantReferenceCache.Resource.AREAS, tenant, "active", () -> {
                if (loads.incrementAndGet() == 1) {
                    started.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    return "stale";
                }
                return "fresh";
            }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var invalidation = executor.submit(() -> subject.invalidateAfterCommit(tenant, TenantReferenceCache.Resource.AREAS));
            release.countDown();
            assertEquals("stale", pending.get(3, TimeUnit.SECONDS));
            invalidation.get(3, TimeUnit.SECONDS);
            assertEquals("fresh", subject.get(TenantReferenceCache.Resource.AREAS, tenant, "active", () -> "fresh"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
