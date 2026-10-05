package com.mopl.realtime.moderation.service;

import static org.assertj.core.api.Assertions.*;

import com.mopl.core.common.enums.*;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.message.entity.ContentChatMessage;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.realtime.moderation.repository.MessageModerationLogRepository;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.ai.model.chat=none"})
@Import({ModerationLogService.class, ModerationContextService.class, ModerationPersistenceTest.Settings.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModerationPersistenceTest {
    @TestConfiguration static class Settings {
        @Bean ModerationProperties properties() { return ModerationTestSettings.defaults(); }
    }
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ModerationLogService logs;
    @Autowired MessageModerationLogRepository repository;
    @Autowired ModerationContextService contexts;
    private UUID userId;
    private UUID contentId;
    private UUID conversationId;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "sender", null, UserRole.USER);
            User other = new User(UUID.randomUUID() + "@example.com", "password", "receiver", null, UserRole.USER);
            Content content = new Content(ContentType.MOVIE, "영화", "설명", null, ExternalSource.MANUAL,
                null, null, null, null, null);
            entityManager.persist(user); entityManager.persist(other); entityManager.persist(content);
            Conversation conversation = new Conversation(user, other); entityManager.persist(conversation);
            entityManager.persist(new ContentChatMessage(content, other, "최근 채팅"));
            entityManager.persist(new DirectMessage(conversation, other, user, "최근 DM"));
            userId = user.getId(); contentId = content.getId(); conversationId = conversation.getId();
        });
    }
    @Test void blockedMessageRollbackDoesNotLoseLogAndBothChannelsCountTogether() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            logs.record(userId, MessageType.DM, conversationId, "씨발", DetectionSource.RULE, ModerationCategory.PROFANITY, ModerationAction.MASK);
            throw new ModerationException(ModerationException.ErrorCode.MESSAGE_BLOCKED);
        })).isInstanceOf(ModerationException.class);
        logs.record(userId, MessageType.CONTENT_CHAT, contentId, "병신", DetectionSource.LLM, ModerationCategory.HARMFUL, ModerationAction.BLOCK);
        assertThat(logs.recentCount(userId)).isEqualTo(2);
        var records = repository.findBySender_IdAndDetectionSourceInAndModerationActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            userId, java.util.List.of(DetectionSource.RULE, DetectionSource.LLM),
            java.util.List.of(ModerationAction.MASK, ModerationAction.BLOCK), LocalDateTime.now().minusMinutes(10),
            org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(records).hasSize(2);
        assertThat(records).extracting(log -> log.getOriginalContent()).containsExactlyInAnyOrder("씨발", "병신");
        assertThat(records).extracting(log -> log.getDetectionSource()).containsExactlyInAnyOrder(DetectionSource.RULE, DetectionSource.LLM);
    }
    @Test void contextIsBoundedAndChannelSpecificWhileViolationsAreShared() {
        logs.record(userId, MessageType.DM, conversationId, "씨발", DetectionSource.RULE, ModerationCategory.PROFANITY, ModerationAction.MASK);
        var dm = contexts.load(userId, MessageType.DM, conversationId, "씨발");
        var chat = contexts.load(userId, MessageType.CONTENT_CHAT, contentId, "병신");
        assertThat(dm.recentConversation()).extracting(ModerationContextService.ContextMessage::text).containsExactly("최근 DM");
        assertThat(chat.recentConversation()).extracting(ModerationContextService.ContextMessage::text).containsExactly("최근 채팅");
        assertThat(chat.violations()).extracting(ModerationContextService.ContextMessage::text).containsExactly("씨발");
        assertThat(dm.violations()).hasSize(1);
    }
    @Test void confirmedRuleAndLlmViolationsReachThresholdTogether() {
        logs.record(userId, MessageType.DM, conversationId, "씨발", DetectionSource.RULE, ModerationCategory.PROFANITY, ModerationAction.MASK);
        logs.record(userId, MessageType.CONTENT_CHAT, contentId, "병신", DetectionSource.RULE, ModerationCategory.PROFANITY, ModerationAction.MASK);
        logs.record(userId, MessageType.DM, conversationId, "꺼져", DetectionSource.LLM, ModerationCategory.HARMFUL, ModerationAction.BLOCK);
        assertThat(logs.recentCount(userId)).isEqualTo(3);
        assertThat(contexts.load(userId, MessageType.DM, conversationId, "현재").violations()).hasSize(3);
    }
    @Test void allowedEvidenceCannotEnterCountOrSanctionContext() {
        tx.executeWithoutResult(status -> entityManager.persist(com.mopl.core.domain.message.entity.MessageModerationLog.forDirectMessage(
            entityManager.getReference(User.class,userId), entityManager.getReference(Conversation.class,conversationId),
            null, DetectionSource.LLM, ModerationCategory.HARMFUL, ModerationAction.ALLOW, "꺼져")));
        assertThat(logs.recentCount(userId)).isZero();
        assertThat(contexts.load(userId,MessageType.DM,conversationId,"현재").violations()).isEmpty();
        assertThatThrownBy(() -> logs.record(userId,MessageType.DM,conversationId,"현재",DetectionSource.LLM,
            ModerationCategory.HARMFUL,ModerationAction.ALLOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void messageContextIsBoundedAndDoesNotIncludeEvidence() {
        var oldMessages = new java.util.ArrayList<UUID>();
        tx.executeWithoutResult(status -> {
            User user=entityManager.getReference(User.class,userId);
            Conversation conversation=entityManager.getReference(Conversation.class,conversationId);
            for(int i=0;i<15;i++) {
                var message = new DirectMessage(conversation,user,user,"과거 메시지");
                entityManager.persist(message);
                oldMessages.add(message.getId());
            }
        });
        for (int i=0;i<oldMessages.size();i++) setCreatedAt(MessageType.DM,oldMessages.get(i),LocalDateTime.of(2020,1,1,0,0).plusSeconds(i));
        logs.record(userId,MessageType.DM,conversationId,"증거 전용",DetectionSource.LLM,ModerationCategory.HARMFUL,ModerationAction.BLOCK);
        UUID currentDm=saveMessage(MessageType.DM,"현재");
        setCreatedAt(MessageType.DM,currentDm,LocalDateTime.of(2030,1,1,0,0));
        var context=contexts.loadMessage(userId,MessageType.DM,conversationId,currentDm,"현재");
        assertThat(context.currentMessage()).isEqualTo("현재");
        assertThat(context.recentConversation()).hasSize(10);
        assertThat(context.recentConversation()).extracting(com.mopl.realtime.moderation.dto.MessageReviewContext.ConversationMessage::text)
            .doesNotContain("증거 전용");
        UUID currentChat=saveMessage(MessageType.CONTENT_CHAT,"현재");
        setCreatedAt(MessageType.CONTENT_CHAT,currentChat,LocalDateTime.of(2030,1,1,0,0));
        var chat=contexts.loadMessage(userId,MessageType.CONTENT_CHAT,contentId,currentChat,"현재");
        assertThat(chat.recentConversation()).extracting(com.mopl.realtime.moderation.dto.MessageReviewContext.ConversationMessage::text)
            .containsExactly("최근 채팅");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(MessageType.class)
    void reviewedViolationLinksMessageCountsAndAppearsInSanctionEvidenceOnce(MessageType type) throws Exception {
        UUID messageId = saveMessage(type, "꺼져");
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); return logs.recordReviewedViolation(messageId,type,"꺼져"); });
            var second = workers.submit(() -> { start.await(); return logs.recordReviewedViolation(messageId,type,"꺼져"); });
            start.countDown();
            assertThat(java.util.List.of(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true,false);
        } finally { workers.shutdownNow(); }
        assertThat(logs.recentCount(userId)).isEqualTo(1);
        assertThat(contexts.load(userId,type,type==MessageType.DM ? conversationId : contentId,"현재").violations())
            .extracting(ModerationContextService.ContextMessage::text).containsExactly("꺼져");
        tx.executeWithoutResult(status -> {
            var record = repository.findBySender_IdAndDetectionSourceInAndModerationActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                userId, java.util.List.of(DetectionSource.LLM), java.util.List.of(ModerationAction.VIOLATION),
                LocalDateTime.now().minusMinutes(10), org.springframework.data.domain.PageRequest.of(0,10)).getFirst();
            assertThat(record.getModerationAction()).isEqualTo(ModerationAction.VIOLATION);
            assertThat(type==MessageType.DM ? record.getDirectMessage().getId() : record.getContentChatMessage().getId()).isEqualTo(messageId);
        });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(MessageType.class)
    void currentIdIsExcludedBeforeLimitAndIdenticalOtherMessageRemains(MessageType type) {
        var older = new java.util.ArrayList<UUID>();
        for (int i=0;i<12;i++) older.add(saveMessage(type, "과거"+i));
        UUID tieA = saveMessage(type, "같은 문자열");
        UUID tieB = saveMessage(type, "같은 문자열");
        UUID current = tieA.toString().compareTo(tieB.toString()) > 0 ? tieA : tieB;
        UUID other = current.equals(tieA) ? tieB : tieA;
        var currentTime = LocalDateTime.of(2021,1,1,0,0);
        for (int i=0;i<older.size();i++) setCreatedAt(type,older.get(i),currentTime.minusMinutes(1).plusSeconds(i));
        setCreatedAt(type,tieA,currentTime);
        setCreatedAt(type,tieB,currentTime);
        var context = contexts.loadMessage(userId,type,type==MessageType.DM ? conversationId : contentId,current,"같은 문자열");
        assertThat(context.recentConversation()).hasSize(10);
        assertThat(context.recentConversation()).extracting(com.mopl.realtime.moderation.dto.MessageReviewContext.ConversationMessage::text)
            .containsOnlyOnce("같은 문자열");
        assertThat(other).isNotEqualTo(current);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(MessageType.class)
    void messageContextIncludesOnlyMessagesBeforeCurrentByTimeAndId(MessageType type) {
        UUID older = saveMessage(type,"시간상 이전");
        UUID tiedA = saveMessage(type,"동일 시각 후보 A");
        UUID tiedB = saveMessage(type,"동일 시각 후보 B");
        UUID current = tiedA.toString().compareTo(tiedB.toString()) > 0 ? tiedA : tiedB;
        UUID tiedEarlier = current.equals(tiedA) ? tiedB : tiedA;
        UUID future = saveMessage(type,"시간상 이후");
        var currentTime = LocalDateTime.of(2021,2,3,4,5);
        setCreatedAt(type,older,currentTime.minusSeconds(1));
        setCreatedAt(type,tiedA,currentTime);
        setCreatedAt(type,tiedB,currentTime);
        setCreatedAt(type,future,currentTime.plusSeconds(1));

        var context = contexts.loadMessage(userId,type,type==MessageType.DM ? conversationId : contentId,current,"현재 판정 대상");
        assertThat(context.currentMessage()).isEqualTo("현재 판정 대상");
        assertThat(context.recentConversation()).extracting(
            com.mopl.realtime.moderation.dto.MessageReviewContext.ConversationMessage::text)
            .containsExactly(entityText(type,tiedEarlier),"시간상 이전");
    }
    @Test void maskBlockAndViolationRemainCompatibleInSharedThreshold() {
        logs.record(userId,MessageType.DM,conversationId,"씨발",DetectionSource.RULE,ModerationCategory.PROFANITY,ModerationAction.MASK);
        logs.record(userId,MessageType.CONTENT_CHAT,contentId,"legacy",DetectionSource.LLM,ModerationCategory.HARMFUL,ModerationAction.BLOCK);
        logs.recordReviewedViolation(saveMessage(MessageType.DM,"꺼져"),MessageType.DM,"꺼져");
        assertThat(logs.recentCount(userId)).isEqualTo(3);
        assertThat(contexts.load(userId,MessageType.DM,conversationId,"현재").violations()).hasSize(3);
    }
    private UUID saveMessage(MessageType type, String text) {
        return tx.execute(status -> {
            User user=entityManager.getReference(User.class,userId);
            if (type==MessageType.DM) {
                var message=new DirectMessage(entityManager.getReference(Conversation.class,conversationId),user,user,text);
                entityManager.persist(message); return message.getId();
            }
            var message=new ContentChatMessage(entityManager.getReference(Content.class,contentId),user,text);
            entityManager.persist(message); return message.getId();
        });
    }
    private void setCreatedAt(MessageType type, UUID id, LocalDateTime createdAt) {
        tx.executeWithoutResult(status -> entityManager.createNativeQuery("update "
            + (type==MessageType.DM ? "direct_messages" : "content_chat_messages")
            + " set created_at = :time where id = :id")
            .setParameter("time",createdAt).setParameter("id",id.toString()).executeUpdate());
    }
    private String entityText(MessageType type, UUID id) {
        return tx.execute(status -> type==MessageType.DM
            ? entityManager.find(DirectMessage.class,id).getContent()
            : entityManager.find(ContentChatMessage.class,id).getMessage());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"DM,ALLOW", "DM,VIOLATION", "CONTENT_CHAT,ALLOW", "CONTENT_CHAT,VIOLATION"})
    void committedAllowedMessageRemainsOriginalAfterReviewAndRollbackDoesNotReview(MessageType type,
        com.mopl.realtime.moderation.dto.MessageReviewDecision.Action action) throws Exception {
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var observed = new java.util.concurrent.atomic.AtomicReference<com.mopl.realtime.moderation.dto.MessageReviewContext>();
        var sanctions = org.mockito.Mockito.mock(com.mopl.realtime.moderation.review.ModerationReviewDispatcher.class);
        var service = new com.mopl.realtime.moderation.review.MessageReviewService(contexts, context -> {
            observed.set(context); started.countDown();
            try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return new com.mopl.realtime.moderation.dto.MessageReviewDecision(
                action);
        },new com.mopl.realtime.moderation.review.MessageReviewRoutingFilter(),logs,sanctions,ModerationTestSettings.defaults(),
            com.mopl.realtime.moderation.support.MessageReviewTestSettings.defaults(),meters,worker,scheduler);
        assertThat(new com.mopl.realtime.moderation.rule.ProfanityRule(ModerationTestSettings.defaults(),
            com.mopl.realtime.moderation.support.MessageReviewTestSettings.defaults()).inspect("가정교육 수준 보인다").action())
            .isEqualTo(com.mopl.realtime.moderation.dto.RuleAction.ALLOW);
        if (action==com.mopl.realtime.moderation.dto.MessageReviewDecision.Action.VIOLATION) {
            logs.record(userId,MessageType.DM,conversationId,"씨발",DetectionSource.RULE,ModerationCategory.PROFANITY,ModerationAction.MASK);
            logs.record(userId,MessageType.CONTENT_CHAT,contentId,"병신",DetectionSource.RULE,ModerationCategory.PROFANITY,ModerationAction.MASK);
        }
        UUID committed;
        try {
            UUID target = type==MessageType.DM ? conversationId : contentId;
            tx.executeWithoutResult(status -> {
                UUID rolledBack = saveMessage(type,"가정교육 수준 보인다");
                service.reviewAfterCommit(userId,type,target,rolledBack,"가정교육 수준 보인다", com.mopl.realtime.moderation.dto.RuleAction.ALLOW);
                assertThat(started.getCount()).isEqualTo(1);
                status.setRollbackOnly();
            });
            worker.submit(() -> { }).get(3,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(started.getCount()).isEqualTo(1);
            committed = tx.execute(status -> {
                UUID id = saveMessage(type,"가정교육 수준 보인다");
                service.reviewAfterCommit(userId,type,target,id,"가정교육 수준 보인다", com.mopl.realtime.moderation.dto.RuleAction.ALLOW);
                assertThat(started.getCount()).isEqualTo(1);
                return id;
            });
            assertThat(started.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get().currentMessage()).isEqualTo("가정교육 수준 보인다");
            assertThat(release.getCount()).isEqualTo(1);
            tx.executeWithoutResult(status -> {
                if (type==MessageType.DM) assertThat(entityManager.find(DirectMessage.class,committed).getContent()).isEqualTo("가정교육 수준 보인다");
                else assertThat(entityManager.find(ContentChatMessage.class,committed).getMessage()).isEqualTo("가정교육 수준 보인다");
            });
            assertThat(observed.get().recentConversation())
                .extracting(com.mopl.realtime.moderation.dto.MessageReviewContext.ConversationMessage::text).doesNotContain("가정교육 수준 보인다");
        } finally {
            release.countDown(); worker.shutdown(); scheduler.shutdownNow();
            assertThat(worker.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            meters.close();
        }
        tx.executeWithoutResult(status -> {
            if (type==MessageType.DM) assertThat(entityManager.find(DirectMessage.class,committed).getContent()).isEqualTo("가정교육 수준 보인다");
            else assertThat(entityManager.find(ContentChatMessage.class,committed).getMessage()).isEqualTo("가정교육 수준 보인다");
        });
        if (action==com.mopl.realtime.moderation.dto.MessageReviewDecision.Action.ALLOW) {
            assertThat(logs.recentCount(userId)).isZero();
            org.mockito.Mockito.verifyNoInteractions(sanctions);
        } else {
            assertThat(logs.recentCount(userId)).isEqualTo(3);
            org.mockito.Mockito.verify(sanctions).submit(userId,type,type==MessageType.DM ? conversationId : contentId,"가정교육 수준 보인다");
            assertThat(contexts.load(userId,type,type==MessageType.DM ? conversationId : contentId,"현재").violations())
                .extracting(ModerationContextService.ContextMessage::text).contains("가정교육 수준 보인다");
        }
    }
    @Test void oldViolationDoesNotCount() {
        logs.record(userId, MessageType.DM, conversationId, "씨발", DetectionSource.RULE, ModerationCategory.PROFANITY, ModerationAction.MASK);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
            "update message_moderation_logs set created_at = :old where sender_id = :sender")
            .setParameter("old", LocalDateTime.now().minusDays(1)).setParameter("sender", userId.toString()).executeUpdate());
        assertThat(logs.recentCount(userId)).isZero();
    }
}
