package com.mopl.realtime.moderation.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mopl.core.common.enums.*;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.message.entity.*;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.contentchat.controller.ContentChatWebSocketController;
import com.mopl.realtime.contentchat.dto.*;
import com.mopl.realtime.contentchat.repository.ContentChatMessageRepository;
import com.mopl.realtime.contentchat.repository.ContentRepository;
import com.mopl.realtime.contentchat.repository.UserRepository;
import com.mopl.realtime.contentchat.service.ContentChatService;
import com.mopl.realtime.directmessage.controller.DirectMessageWebSocketController;
import com.mopl.realtime.directmessage.dto.*;
import com.mopl.realtime.directmessage.repository.*;
import com.mopl.realtime.directmessage.service.DirectMessageService;
import com.mopl.realtime.moderation.dto.*;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore;
import com.mopl.realtime.moderation.review.*;
import com.mopl.realtime.moderation.rule.ProfanityRule;
import com.mopl.realtime.moderation.support.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
class ModeratedMessageDeliveryTest {
    private final UUID userId=UUID.randomUUID();
    private final UUID targetId=UUID.randomUUID();
    private final User sender=mock(User.class);
    private final User receiver=mock(User.class);
    private final Content content=mock(Content.class);
    private final Conversation conversation=mock(Conversation.class);
    private final ContentRepository contents=mock(ContentRepository.class);
    private final UserRepository users=mock(UserRepository.class);
    private final ContentChatMessageRepository chats=mock(ContentChatMessageRepository.class);
    private final ConversationRepository conversations=mock(ConversationRepository.class);
    private final DirectMessageRepository messages=mock(DirectMessageRepository.class);
    private final MessageReviewService messageReview=mock(MessageReviewService.class);
    private final ModerationLogService logs=mock(ModerationLogService.class);
    private final ModerationReviewDispatcher sanctions=mock(ModerationReviewDispatcher.class);
    private final ChatRestrictionStore restrictions=mock(ChatRestrictionStore.class);
    private final SimpMessagingTemplate broadcasts=mock(SimpMessagingTemplate.class);
    private final ApplicationEventPublisher eventPublisher=mock(ApplicationEventPublisher.class);
    private ContentChatWebSocketController chatController;
    private DirectMessageWebSocketController dmController;
    @BeforeEach void setup() {
        when(sender.getId()).thenReturn(userId);
        when(receiver.getId()).thenReturn(UUID.randomUUID());
        when(conversation.getId()).thenReturn(targetId);
        when(conversation.getUser1()).thenReturn(sender);when(conversation.getUser2()).thenReturn(receiver);
        when(contents.findByIdAndDeletedAtIsNull(targetId)).thenReturn(Optional.of(content));
        when(users.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(sender));
        when(conversations.findWithParticipantsById(targetId)).thenReturn(Optional.of(conversation));
        when(chats.save(any(ContentChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messages.save(any(DirectMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        configureControllers(messageReview, new SimpleMeterRegistry());
    }
    private void configureControllers(MessageReviewService reviews, SimpleMeterRegistry meters) {
        var settings=ModerationTestSettings.defaults();
        var moderation=new MessageModerationService(restrictions,new ProfanityRule(settings,MessageReviewTestSettings.defaults()),
            reviews,logs,sanctions,settings,meters);
        chatController=new ContentChatWebSocketController(new ContentChatService(contents,users,chats,moderation),broadcasts,meters);
        dmController=new DirectMessageWebSocketController(new DirectMessageService(conversations,messages,moderation,eventPublisher),broadcasts,meters);
    }
    @ParameterizedTest @EnumSource(MessageType.class)
    void ruleMasksBeforePersistenceAndBroadcastWhileEvidenceRetainsOriginal(MessageType type) {
        send(type,"너는 병신!");
        assertDelivery(type,"너는 **!");
        verify(logs).record(userId,type,targetId,"너는 병신!",DetectionSource.RULE,ModerationCategory.PROFANITY,ModerationAction.MASK);
        verifyNoInteractions(messageReview);
    }
    @ParameterizedTest @EnumSource(MessageType.class)
    void reviewPersistsAndBroadcastsOriginalAndOnlySchedulesAfterSave(MessageType type) {
        send(type,"꺼져");
        assertDelivery(type,"꺼져");
        var order = inOrder(type == MessageType.DM ? messages : chats, messageReview, broadcasts);
        if (type == MessageType.DM) order.verify(messages).save(any(DirectMessage.class));
        else order.verify(chats).save(any(ContentChatMessage.class));
        order.verify(messageReview).reviewAfterCommit(eq(userId),eq(type),eq(targetId),any(),eq("꺼져"),eq(RuleAction.REVIEW));
        order.verify(broadcasts).convertAndSend(anyString(),any(Object.class));
        verifyNoInteractions(logs,sanctions);
    }
    static Stream<Arguments> allowedMessages() {
        return Stream.of(MessageType.values()).flatMap(type -> Stream.of(
            "가정교육 수준 보인다", "너는 어떻게 자랐길래 그러냐", "부모 얼굴이 궁금하다")
            .map(text -> Arguments.of(type, text)));
    }
    @ParameterizedTest @MethodSource("allowedMessages")
    void allowPersistsAndBroadcastsOriginalAndSchedulesAfterSave(MessageType type, String text) {
        assertThat(new ProfanityRule(ModerationTestSettings.defaults(), MessageReviewTestSettings.defaults())
            .inspect(text).action()).isEqualTo(RuleAction.ALLOW);
        send(type,text);
        assertDelivery(type,text);
        var order = inOrder(type == MessageType.DM ? messages : chats, messageReview, broadcasts);
        if (type == MessageType.DM) order.verify(messages).save(any(DirectMessage.class));
        else order.verify(chats).save(any(ContentChatMessage.class));
        order.verify(messageReview).reviewAfterCommit(eq(userId),eq(type),eq(targetId),any(),eq(text),eq(RuleAction.ALLOW));
        order.verify(broadcasts).convertAndSend(anyString(),any(Object.class));
        verifyNoInteractions(logs,sanctions);
    }
    static Stream<Arguments> unmaskedReviewResults() {
        return Stream.of(MessageType.values()).flatMap(type -> Stream.of("가정교육 수준 보인다", "꺼져")
            .flatMap(text -> Stream.of(MessageReviewDecision.Action.values()).map(action -> Arguments.of(type,text,action))));
    }
    @ParameterizedTest @MethodSource("unmaskedReviewResults")
    void unmaskedMessageIsReviewedAfterCommitWithoutWaitingAndViolationReachesThreshold(
        MessageType type, String text, MessageReviewDecision.Action action) throws Exception {
        var contexts=mock(ModerationContextService.class);
        var worker=Executors.newSingleThreadExecutor();
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        var meters=new SimpleMeterRegistry();
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var context=new MessageReviewContext(text,List.of(new MessageReviewContext.ConversationMessage(false,"왜 그렇게 말해?")));
        when(contexts.loadMessage(eq(userId),eq(type),eq(targetId),any(),eq(text))).thenReturn(context);
        when(logs.recordReviewedViolation(any(),eq(type),eq(text))).thenReturn(true);
        when(logs.recentCount(userId)).thenReturn(3L);
        var reviews=new MessageReviewService(contexts, request -> {
            assertThat(request).isEqualTo(context);
            started.countDown();
            try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return new MessageReviewDecision(action);
        },new MessageReviewRoutingFilter(),logs,sanctions,ModerationTestSettings.defaults(),MessageReviewTestSettings.defaults(),meters,worker,scheduler);
        configureControllers(reviews,meters);
        try {
            transaction().executeWithoutResult(status -> {
                send(type,text);
                assertDelivery(type,text);
                verifyNoInteractions(contexts,logs,sanctions);
            });
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
            verifyNoInteractions(logs,sanctions);
            assertThat(meters.get("realtime.message.send").tags("channel",type==MessageType.DM ? "dm" : "chat",
                "outcome","success").timer().count()).isEqualTo(1);
            release.countDown();
            worker.submit(() -> {}).get(3,TimeUnit.SECONDS);
            assertThat(meters.get("moderation.message.llm").timer().count()).isEqualTo(1);
            String outcome=action.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(meters.get("moderation.message.reviews").tag("outcome",outcome).counter().count()).isEqualTo(1);
            assertThat(meters.get("moderation.message.review").tag("outcome",outcome).timer().count()).isEqualTo(1);
            if (action==MessageReviewDecision.Action.ALLOW) verifyNoInteractions(logs,sanctions);
            else {
                verify(logs).recordReviewedViolation(any(),eq(type),eq(text));
                verify(sanctions).submit(userId,type,targetId,text);
            }
            verifyNoMoreInteractions(broadcasts);
        } finally {
            release.countDown(); worker.shutdownNow(); scheduler.shutdownNow();
            assertThat(worker.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            meters.close();
        }
    }
    @ParameterizedTest @EnumSource(MessageType.class)
    void allowedMessageRollbackNeverSubmitsReview(MessageType type) {
        var contexts=mock(ModerationContextService.class);
        var reviewer=mock(MessageReviewer.class);
        var worker=mock(ExecutorService.class);
        var scheduler=mock(ScheduledExecutorService.class);
        var meters=new SimpleMeterRegistry();
        try {
            configureControllers(new MessageReviewService(contexts,reviewer,new MessageReviewRoutingFilter(),logs,sanctions,
                ModerationTestSettings.defaults(),MessageReviewTestSettings.defaults(),meters,worker,scheduler),meters);
            transaction().executeWithoutResult(status -> {
                send(type,"부모 얼굴이 궁금하다");
                status.setRollbackOnly();
            });
            verifyNoInteractions(worker,scheduler,contexts,reviewer,logs,sanctions);
        } finally { meters.close(); }
    }
    static Stream<Arguments> reviewFailures() {
        return Stream.of(MessageType.values()).flatMap(type -> Stream.of("timeout","error","invalid_response","queue_full")
            .map(outcome -> Arguments.of(type,outcome)));
    }
    @ParameterizedTest @MethodSource("reviewFailures")
    void allowedMessageReviewFailureLeavesOriginalDeliveryAndNoViolation(MessageType type, String outcome) throws Exception {
        String text="부모 얼굴이 궁금하다";
        var contexts=mock(ModerationContextService.class);
        var worker=Executors.newSingleThreadExecutor();
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        var meters=new SimpleMeterRegistry();
        var rejected=mock(ExecutorService.class);
        doThrow(new RejectedExecutionException()).when(rejected).execute(any(Runnable.class));
        when(contexts.loadMessage(eq(userId),eq(type),eq(targetId),any(),eq(text)))
            .thenReturn(new MessageReviewContext(text,List.of()));
        var reviewer=mock(MessageReviewer.class);
        when(reviewer.review(any())).thenAnswer(invocation -> {
            if (outcome.equals("timeout")) throw new IllegalStateException(new java.net.SocketTimeoutException());
            if (outcome.equals("invalid_response")) throw new com.mopl.realtime.moderation.exception.InvalidMessageReviewResponseException();
            throw new IllegalStateException("model error");
        });
        configureControllers(new MessageReviewService(contexts,reviewer,new MessageReviewRoutingFilter(),logs,sanctions,ModerationTestSettings.defaults(),
            MessageReviewTestSettings.defaults(),meters,outcome.equals("queue_full") ? rejected : worker,scheduler),meters);
        try {
            transaction().executeWithoutResult(status -> {
                send(type,text);
                assertDelivery(type,text);
                verifyNoInteractions(contexts,reviewer,logs,sanctions);
            });
            worker.submit(() -> {}).get(3,TimeUnit.SECONDS);
            assertThat(meters.get("moderation.message.reviews").tag("outcome",outcome).counter().count()).isEqualTo(1);
            assertThat(meters.find("moderation.message.reviews").tag("outcome","violation").counter()).isNull();
            verifyNoInteractions(logs,sanctions);
            verifyNoMoreInteractions(broadcasts);
            assertThat(meters.get("realtime.message.send").tags("channel",type==MessageType.DM ? "dm" : "chat",
                "outcome","success").timer().count()).isEqualTo(1);
            if (outcome.equals("queue_full")) verifyNoInteractions(contexts,reviewer);
        } finally {
            worker.shutdownNow(); scheduler.shutdownNow();
            assertThat(worker.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            meters.close();
        }
    }
    private TransactionTemplate transaction() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        });
    }
    @ParameterizedTest @EnumSource(MessageType.class)
    void restrictionBlocksBothChannelsAndAllowsDeliveryAfterExpiry(MessageType type) {
        when(restrictions.restriction(userId)).thenReturn(new ChatRestrictionStore.Restriction(com.mopl.realtime.moderation.dto.SanctionLevel.TEMPORARY_SHORT, java.time.Instant.now().plusSeconds(600)));
        var exception = catchThrowableOfType(() -> send(type,"정상 메시지"), ModerationException.class);
        var response = new com.mopl.realtime.moderation.handler.ModerationErrorHandler().handle(exception);
        assertThat(response.code()).isEqualTo("CHAT_RESTRICTED");
        assertThat(response.message()).contains("메시지 이용 정책 위반");
        assertThat(response.restrictionLevel()).isEqualTo(SanctionLevel.TEMPORARY_SHORT);
        assertThat(response.restrictedUntil()).isEqualTo(restrictions.restriction(userId).restrictedUntil());
        verify(chats,never()).save(any());verify(messages,never()).save(any());
        verifyNoInteractions(broadcasts,messageReview,logs,sanctions);
        when(restrictions.restriction(userId)).thenReturn(null);
        send(type,"만료 후 정상 메시지");
        assertDelivery(type,"만료 후 정상 메시지");
        verifyNoInteractions(logs,sanctions);
    }
    @ParameterizedTest @EnumSource(MessageType.class)
    void restrictionLookupFailureUsesDistinctErrorAndRecoveryAllowsBothChannels(MessageType type) {
        when(restrictions.restriction(userId)).thenThrow(new IllegalStateException("redis unavailable"));
        var exception = catchThrowableOfType(() -> send(type,"첫 메시지"), ModerationException.class);
        var response = new com.mopl.realtime.moderation.handler.ModerationErrorHandler().handle(exception);
        assertThat(exception.getErrorCode()).isEqualTo(ModerationException.ErrorCode.CHAT_RESTRICTION_CHECK_FAILED);
        assertThat(response.code()).isEqualTo("CHAT_RESTRICTION_CHECK_FAILED");
        assertThat(response.message()).isEqualTo("채팅 상태를 확인하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        assertThat(response.restrictionLevel()).isNull();
        assertThat(response.restrictedUntil()).isNull();
        verify(chats,never()).save(any());verify(messages,never()).save(any());
        verifyNoInteractions(broadcasts,messageReview,logs,sanctions);

        doReturn(null).when(restrictions).restriction(userId);
        send(type,"복구 후 정상 메시지");
        assertDelivery(type,"복구 후 정상 메시지");
    }
    private void send(MessageType type,String text) {
        Principal principal=() -> userId.toString();
        if(type==MessageType.DM) dmController.send(targetId,new DirectMessageSendRequest(text),principal);
        else chatController.send(targetId,new ContentChatSendRequest(text),principal);
    }
    private void assertDelivery(MessageType type,String expected) {
        if(type==MessageType.DM) {
            var saved=ArgumentCaptor.forClass(DirectMessage.class);verify(messages).save(saved.capture());
            assertThat(saved.getValue().getContent()).isEqualTo(expected);
            var response=ArgumentCaptor.forClass(DirectMessageResponse.class);
            verify(broadcasts).convertAndSend(eq("/sub/conversations/"+targetId+"/direct-messages"),response.capture());
            assertThat(response.getValue().content()).isEqualTo(expected);
        } else {
            var saved=ArgumentCaptor.forClass(ContentChatMessage.class);verify(chats).save(saved.capture());
            assertThat(saved.getValue().getMessage()).isEqualTo(expected);
            var response=ArgumentCaptor.forClass(ContentChatResponse.class);
            verify(broadcasts).convertAndSend(eq("/sub/contents/"+targetId+"/chat"),response.capture());
            assertThat(response.getValue().content()).isEqualTo(expected);
        }
    }
}
