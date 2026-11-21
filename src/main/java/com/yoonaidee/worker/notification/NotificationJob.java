package com.yoonaidee.worker.notification;
import java.util.UUID;
public record NotificationJob(UUID id, String lineUserId, String payloadJson) {}
