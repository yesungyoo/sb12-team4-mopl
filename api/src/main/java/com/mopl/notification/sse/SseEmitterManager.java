package com.mopl.notification.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SseEmitterManager {

  private static final long DEFAULT_TIMEOUT = 30 * 60 * 1000L; // 30분

  private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

  public SseEmitter connect(UUID userId) {
    SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);

    emitters.put(userId, emitter);

    emitter.onCompletion(() -> {
      log.info("SSE 연결 종료 (완료) - userId: {}", userId);
      emitters.remove(userId, emitter);
    });

    emitter.onTimeout(() -> {
      log.info("SSE 연결 종료 (타임아웃) - userId: {}", userId);
      emitters.remove(userId, emitter);
    });

    emitter.onError((e) -> {
      log.warn("SSE 연결 에러 - userId: {}", userId, e);
      emitters.remove(userId, emitter);
    });

    return emitter;
  }

  public void send(UUID userId, String eventName, Object data, String eventId) {
    SseEmitter emitter = emitters.get(userId);

    if (emitter == null) {
      log.debug("SSE 연결 없음 - userId: {}", userId);
      return;
    }

    try {
      emitter.send(SseEmitter.event()
              .id(eventId)
          .name(eventName)
          .data(data));
    } catch (IOException e) {
      log.warn("SSE 전송 실패 - userId: {}", userId, e);
      emitters.remove(userId, emitter);
    }
  }
}