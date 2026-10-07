package com.ecabin.ledger.security;

import java.util.UUID;

/** A principal created only after token validation and active organization-membership resolution. */
public record AuthenticatedUser(UUID organizationId, String externalUserId, String displayName, AppRole role) {}
