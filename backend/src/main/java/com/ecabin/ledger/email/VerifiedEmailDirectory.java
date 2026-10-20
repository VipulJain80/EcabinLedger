package com.ecabin.ledger.email;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Implement only against the organization-approved profile source and its verification flag. */
public interface VerifiedEmailDirectory {
    Map<String, VerifiedAddress> findVerified(UUID tenantId, Collection<String> externalUserIds);

    record VerifiedAddress(String address, boolean verified) {}
}
