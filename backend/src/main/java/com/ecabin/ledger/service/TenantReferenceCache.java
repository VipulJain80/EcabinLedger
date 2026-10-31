package com.ecabin.ledger.service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded process-local cache for explicitly approved reference/configuration reads only. */
@Component
public class TenantReferenceCache {
    public enum Resource {
        CATEGORIES, AREAS, COMPONENTS, CONDITIONS, SEVERITIES, FLEETS, AIRCRAFT_TYPES,
        PUBLISHED_FORMS, FORM_OPTIONS, WORKFLOW_STATUSES;
        Duration ttl(ReferenceCacheProperties p) {
            return switch (this) {
                case CATEGORIES -> p.getCategoriesTtl(); case AREAS -> p.getAreasTtl();
                case COMPONENTS -> p.getComponentsTtl(); case CONDITIONS -> p.getConditionsTtl();
                case SEVERITIES -> p.getSeveritiesTtl(); case FLEETS -> p.getFleetsTtl();
                case AIRCRAFT_TYPES -> p.getAircraftTypesTtl(); case PUBLISHED_FORMS -> p.getPublishedFormsTtl();
                case FORM_OPTIONS -> p.getFormOptionsTtl(); case WORKFLOW_STATUSES -> p.getWorkflowStatusesTtl();
            };
        }
    }
    public record Key(UUID organizationId, String discriminator) {
        public Key { if (discriminator == null || discriminator.isBlank()) throw new IllegalArgumentException("A cache discriminator is required."); }
    }
    private record ScopedResource(UUID organizationId, Resource resource) {}
    private record GlobalResource(Resource resource) {}
    private static final Object TRANSACTION_BATCH_KEY = TenantReferenceCache.class;
    private static final class PendingInvalidations {
        final Set<ScopedResource> scoped = new HashSet<>();
        final Set<GlobalResource> global = new HashSet<>();
    }

    private final Map<Resource, Cache<Key, Object>> caches;
    // Fixed lock stripes coordinate loaders with invalidation without retaining tenant IDs forever.
    private final Object[] locks = new Object[64];

    public TenantReferenceCache(ReferenceCacheRegistry registry) {
        this.caches = registry.caches();
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Resource resource, UUID organizationId, String discriminator, Supplier<T> loader) {
        requireScope(resource, organizationId);
        Cache<Key, Object> cache = caches.get(resource);
        synchronized (lockFor(resource, organizationId)) {
            Key key = new Key(organizationId, discriminator);
            T value = (T) cache.get(key, ignored -> loader.get());
            return value;
        }
    }

    /** Loads fully before replacing the prior entry. A loader failure leaves the last good value intact. */
    public <T> T refresh(Resource resource, UUID organizationId, String discriminator, Supplier<T> loader) {
        requireScope(resource, organizationId);
        synchronized (lockFor(resource, organizationId)) {
            Key key = new Key(organizationId, discriminator);
            T value = loader.get();
            caches.get(resource).put(key, value);
            return value;
        }
    }

    public void invalidateAfterCommit(UUID organizationId, Resource... resources) {
        Set<Resource> requested = Set.of(resources);
        if (!TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.isActualTransactionActive()) {
            requested.forEach(resource -> invalidateTenantNow(organizationId, resource));
            return;
        }
        PendingInvalidations pending = (PendingInvalidations) TransactionSynchronizationManager.getResource(TRANSACTION_BATCH_KEY);
        if (pending == null) {
            pending = new PendingInvalidations();
            TransactionSynchronizationManager.bindResource(TRANSACTION_BATCH_KEY, pending);
            PendingInvalidations registered = pending;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    registered.scoped.forEach(value -> invalidateTenantNow(value.organizationId(), value.resource()));
                    registered.global.forEach(value -> invalidateGlobalNow(value.resource()));
                }
                @Override public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TRANSACTION_BATCH_KEY)) TransactionSynchronizationManager.unbindResource(TRANSACTION_BATCH_KEY);
                }
            });
        }
        PendingInvalidations batch=pending;
        requested.forEach(resource -> batch.scoped.add(new ScopedResource(organizationId, resource)));
    }

    public void invalidateGlobalAfterCommit(Resource... resources) {
        Set<Resource> requested = Set.of(resources);
        if (!TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.isActualTransactionActive()) {
            requested.forEach(this::invalidateGlobalNow);
            return;
        }
        PendingInvalidations pending = (PendingInvalidations) TransactionSynchronizationManager.getResource(TRANSACTION_BATCH_KEY);
        if (pending == null) {
            pending = new PendingInvalidations();
            TransactionSynchronizationManager.bindResource(TRANSACTION_BATCH_KEY, pending);
            PendingInvalidations registered = pending;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    registered.scoped.forEach(value -> invalidateTenantNow(value.organizationId(), value.resource()));
                    registered.global.forEach(value -> invalidateGlobalNow(value.resource()));
                }
                @Override public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TRANSACTION_BATCH_KEY)) TransactionSynchronizationManager.unbindResource(TRANSACTION_BATCH_KEY);
                }
            });
        }
        PendingInvalidations batch=pending;
        requested.forEach(resource -> batch.global.add(new GlobalResource(resource)));
    }

    private void invalidateTenantNow(UUID organizationId, Resource resource) {
        synchronized (lockFor(resource, organizationId)) {
            caches.get(resource).asMap().keySet().removeIf(key -> organizationId.equals(key.organizationId()));
        }
    }
    private void invalidateGlobalNow(Resource resource) {
        synchronized (lockFor(resource, null)) {
            caches.get(resource).invalidateAll();
        }
    }

    private Object lockFor(Resource resource, UUID organizationId) {
        int hash = 31 * resource.hashCode() + (organizationId == null ? 0 : organizationId.hashCode());
        return locks[(hash & Integer.MAX_VALUE) % locks.length];
    }

    private void requireScope(Resource resource, UUID organizationId) {
        if (resource == Resource.WORKFLOW_STATUSES ? organizationId != null : organizationId == null)
            throw new IllegalArgumentException(resource == Resource.WORKFLOW_STATUSES
                ? "Workflow-status cache entries are global and cannot use an organization key."
                : "A trusted organization identifier is required for this cache resource.");
    }
}
