package com.malaysia.restaurant.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RealtimeEventService implements MessageListener {
    public static final String CHANNEL = "restaurant:realtime:events";
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private final String nodeId = java.util.UUID.randomUUID().toString();
    private final AtomicLong clientIds = new AtomicLong();
    private final Map<Long, Client> clients = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;

    public RealtimeEventService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public SseEmitter subscribe(AuthService.AuthContext user) {
        long id = clientIds.incrementAndGet();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Client client = new Client(id, user, emitter);
        clients.put(id, client);
        emitter.onCompletion(() -> clients.remove(id));
        emitter.onTimeout(() -> clients.remove(id));
        emitter.onError(error -> clients.remove(id));
        send(client, "CONNECTED", event("CONNECTED", user.merchantId(), user.storeId(), null, Map.of("clientId", id)));
        return emitter;
    }

    public void tableChanged(Long merchantId, Long storeId, long tableId, String action) {
        publish("TABLE_CHANGED", event(action, merchantId, storeId, tableId, Map.of("tableId", tableId)));
    }

    public void orderChanged(Long merchantId, Long storeId, long orderId, String action) {
        publish("ORDER_CHANGED", event(action, merchantId, storeId, orderId, Map.of("orderId", orderId)));
    }

    public void printTaskChanged(Long merchantId, Long storeId, long taskId, String status) {
        publish("PRINT_TASK_CHANGED", event("PRINT_" + status, merchantId, storeId, taskId,
                Map.of("taskId", taskId, "status", status)));
    }

    public void publish(String eventName, RealtimeEvent event) {
        publishLocal(eventName, event);
        try {
            redis.convertAndSend(CHANNEL, encode(eventName, event));
        } catch (Exception ignored) {
            // Local SSE clients already received the event; cross-node Redis fan-out can recover on the next event.
        }
    }

    public void publishLocal(String eventName, RealtimeEvent event) {
        for (Client client : clients.values()) {
            if (canSee(client.user(), event)) {
                send(client, eventName, event);
            }
        }
    }

    @Scheduled(fixedDelay = 25000)
    public void heartbeat() {
        RealtimeEvent heartbeat = event("HEARTBEAT", null, null, null, Map.of());
        for (Client client : clients.values()) {
            send(client, "HEARTBEAT", heartbeat);
        }
    }

    private RealtimeEvent event(String type, Long merchantId, Long storeId, Long resourceId, Map<String, Object> payload) {
        return new RealtimeEvent(type, merchantId, storeId, resourceId, payload, LocalDateTime.now());
    }

    private boolean canSee(AuthService.AuthContext user, RealtimeEvent event) {
        if (user.merchantId() != null && event.merchantId() != null && !user.merchantId().equals(event.merchantId())) {
            return false;
        }
        return user.storeId() == null || event.storeId() == null || user.storeId().equals(event.storeId());
    }

    private void send(Client client, String name, RealtimeEvent event) {
        try {
            client.emitter().send(SseEmitter.event().name(name).data(event));
        } catch (IOException | IllegalStateException e) {
            clients.remove(client.id());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String text = new String(message.getBody(), StandardCharsets.UTF_8);
        int split = text.indexOf('|');
        if (split <= 0) {
            return;
        }
        String name = text.substring(0, split);
        String[] parts = text.substring(split + 1).split("\\|", -1);
        if (parts.length < 5 || nodeId.equals(parts[0])) {
            return;
        }
        Long merchantId = parseLong(parts[2]);
        Long storeId = parseLong(parts[3]);
        Long resourceId = parseLong(parts[4]);
        publishLocal(name, event(parts[1], merchantId, storeId, resourceId, Map.of("source", "redis")));
    }

    private String encode(String eventName, RealtimeEvent event) {
        return eventName + "|" + nodeId + "|" + event.type() + "|" + value(event.merchantId()) + "|" + value(event.storeId())
                + "|" + value(event.resourceId());
    }

    private String value(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    public record RealtimeEvent(String type, Long merchantId, Long storeId, Long resourceId,
                                Map<String, Object> payload, LocalDateTime occurredAt) {
    }

    private record Client(long id, AuthService.AuthContext user, SseEmitter emitter) {
    }
}
