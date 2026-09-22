package com.mopl.notification.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class SseEmitterManager {

  private static final long DEFAULT_TIMEOUT = 30 * 60 * 1000L; // 30분

  private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public SseEmitter connect(UUID userId) {
    SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);

    emitters.compute(userId, (key, existing) -> {
      Set<SseEmitter> userEmitters = existing != null ? existing : new CopyOnWriteArraySet<>();
      userEmitters.add(emitter);
      return userEmitters;
    });

    emitter.onCompletion(() -> {
      log.info("SSE 연결 종료 (완료) - userId: {}", userId);
      removeEmitter(userId, emitter);
    });

    emitter.onTimeout(() -> {
      log.info("SSE 연결 종료 (타임아웃) - userId: {}", userId);
      removeEmitter(userId, emitter);
    });

    emitter.onError((e) -> {
      log.warn("SSE 연결 에러 - userId: {}", userId, e);
      removeEmitter(userId, emitter);
    });

    return emitter;
  }

  public void send(UUID userId, String eventName, Object data, String eventId) {
    Set<SseEmitter> userEmitters = emitters.get(userId);

    if (userEmitters == null || userEmitters.isEmpty()) {
      log.debug("SSE 연결 없음 - userId: {}", userId);
      return;
    }

    for (SseEmitter emitter : userEmitters) {
      try {
        emitter.send(SseEmitter.event()
            .id(eventId)
            .name(eventName)
            .data(data));
      } catch (IOException e) {
        log.warn("SSE 전송 실패 - userId: {}", userId, e);
        removeEmitter(userId, emitter);
      }
    }
  }

  private void removeEmitter(UUID userId, SseEmitter emitter) {
    emitters.computeIfPresent(userId, (key, userEmitters) -> {
      userEmitters.remove(emitter);
      return userEmitters.isEmpty() ? null : userEmitters;
    });
  }

  public void sendHeartbeatToAll() {
    for (Map.Entry<UUID, Set<SseEmitter>> entry : emitters.entrySet()) {
      UUID userId = entry.getKey();
      for (SseEmitter emitter : entry.getValue()) {
        try {
          emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException e) {
          log.debug("Heartbeat 전송 실패 - userId: {}", userId);
          removeEmitter(userId, emitter);
        }
      }
    }
  }
}