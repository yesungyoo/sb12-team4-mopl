package com.mopl.notification.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseHeartbeatScheduler {

  private final SseEmitterManager sseEmitterManager;

  @Scheduled(fixedDelay = 15 * 1000L) // 15초마다
  public void sendHeartbeat() {
    sseEmitterManager.sendHeartbeatToAll();
  }
}