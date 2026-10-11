package com.ecabin.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationModels {
    private NotificationModels() {}
    public record InboxItem(UUID id, String type, String message, UUID defectId, String defectReference,
                            Instant createdAt, Instant readAt) {}
    public record Inbox(long unreadCount, List<InboxItem> notifications) {}
}
