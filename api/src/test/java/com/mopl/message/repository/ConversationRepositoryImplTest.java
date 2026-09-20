package com.mopl.message.repository;

import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.message.dto.SortDirection;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
	replace = AutoConfigureTestDatabase.Replace.ANY
)
@EnableJpaRepositories(basePackageClasses = ConversationRepository.class)
@Import(ConversationRepositoryImplTest.QuerydslTestConfig.class)
class ConversationRepositoryImplTest {

	@TestConfiguration
	static class QuerydslTestConfig {

		@PersistenceContext
		private EntityManager entityManager;

		@Bean
		JPAQueryFactory jpaQueryFactory() {
			return new JPAQueryFactory(entityManager);
		}
	}

	@Autowired
	private TestEntityManager em;

	@Autowired
	private ConversationRepository conversationRepository;

	@Test
	@DisplayName("DM이 0개인 Conversation도 목록 조회 결과와 totalCount에 포함된다")
	void findConversationsByCursor_includesConversationWithoutMessages() {
		User me = persistUser("me@mopl.io", "나");
		User withMessage = persistUser("with-message@mopl.io", "메시지있음");
		User withoutMessage = persistUser("without-message@mopl.io", "메시지없음");

		Conversation conversationWithMessage = persistConversation(me, withMessage);
		persistDirectMessage(conversationWithMessage, me, withMessage, "안녕하세요");

		Conversation conversationWithoutMessage = persistConversation(me, withoutMessage);

		em.flush();
		em.clear();

		List<ConversationListRow> rows = conversationRepository.findConversationsByCursor(
			me.getId(), null, null, null, 10, SortDirection.DESCENDING
		);

		assertThat(rows).hasSize(2);
		assertThat(rows)
			.extracting(row -> row.conversation().getId())
			.containsExactlyInAnyOrder(conversationWithMessage.getId(), conversationWithoutMessage.getId());

		ConversationListRow noMessageRow = rows.stream()
			.filter(row -> row.conversation().getId().equals(conversationWithoutMessage.getId()))
			.findFirst()
			.orElseThrow();
		assertThat(noMessageRow.lastMessage()).isNull();

		long totalCount = conversationRepository.countConversations(me.getId(), null);
		assertThat(totalCount).isEqualTo(2);
	}

	@Test
	@DisplayName("메시지 없는 대화방은 정렬 방향과 무관하게 목록 뒤쪽에 위치한다")
	void findConversationsByCursor_noMessageConversationSortedLast() {
		User me = persistUser("me2@mopl.io", "나");
		User withMessage = persistUser("with-message2@mopl.io", "메시지있음");
		User withoutMessage = persistUser("without-message2@mopl.io", "메시지없음");

		Conversation conversationWithMessage = persistConversation(me, withMessage);
		persistDirectMessage(conversationWithMessage, me, withMessage, "안녕하세요");

		Conversation conversationWithoutMessage = persistConversation(me, withoutMessage);

		em.flush();
		em.clear();

		List<ConversationListRow> descendingRows = conversationRepository.findConversationsByCursor(
			me.getId(), null, null, null, 10, SortDirection.DESCENDING
		);
		assertThat(descendingRows).hasSize(2);
		assertThat(descendingRows.get(descendingRows.size() - 1).conversation().getId())
			.isEqualTo(conversationWithoutMessage.getId());

		List<ConversationListRow> ascendingRows = conversationRepository.findConversationsByCursor(
			me.getId(), null, null, null, 10, SortDirection.ASCENDING
		);
		assertThat(ascendingRows).hasSize(2);
		assertThat(ascendingRows.get(ascendingRows.size() - 1).conversation().getId())
			.isEqualTo(conversationWithoutMessage.getId());
	}

	@Test
	@DisplayName("메시지 없는 대화방도 전용 커서로 중복이나 누락 없이 페이지네이션된다")
	void findConversationsByCursor_noMessageCursorPaginatesWithoutDuplicatesOrMissing() {
		User me = persistUser("cursor-me@mopl.io", "나");
		User user1 = persistUser("cursor-user1@mopl.io", "상대1");
		User user2 = persistUser("cursor-user2@mopl.io", "상대2");
		User user3 = persistUser("cursor-user3@mopl.io", "상대3");

		Conversation conversation1 = persistConversation(me, user1);
		Conversation conversation2 = persistConversation(me, user2);
		Conversation conversation3 = persistConversation(me, user3);

		em.flush();
		em.clear();

		// 1페이지: 빈 대화방 3개 중 2개 조회
		List<ConversationListRow> firstPage =
			conversationRepository.findConversationsByCursor(
				me.getId(),
				null,
				null,
				null,
				2,
				SortDirection.DESCENDING
			);

		assertThat(firstPage).hasSize(2);
		assertThat(firstPage)
			.allSatisfy(row -> assertThat(row.lastMessage()).isNull());

		// 1페이지 마지막 대화방의 id를 보조 커서로 사용
		UUID lastIdOfFirstPage =
			firstPage.get(firstPage.size() - 1).conversation().getId();

		// 2페이지: 빈 대화방 전용 커서로 나머지 조회
		List<ConversationListRow> secondPage =
			conversationRepository.findConversationsByCursor(
				me.getId(),
				null,
				ConversationRepositoryCustom.NO_LAST_MESSAGE_CURSOR,
				lastIdOfFirstPage,
				2,
				SortDirection.DESCENDING
			);

		assertThat(secondPage).hasSize(1);
		assertThat(secondPage.get(0).lastMessage()).isNull();

		// 1, 2페이지의 전체 id를 합쳐서 중복/누락 확인
		List<UUID> allIds = new java.util.ArrayList<>();

		allIds.addAll(
			firstPage.stream()
				.map(row -> row.conversation().getId())
				.toList()
		);

		allIds.addAll(
			secondPage.stream()
				.map(row -> row.conversation().getId())
				.toList()
		);

		assertThat(allIds)
			.containsExactlyInAnyOrder(
				conversation1.getId(),
				conversation2.getId(),
				conversation3.getId()
			)
			.doesNotHaveDuplicates();
	}

	private User persistUser(String email, String name) {
		User user = new User(email, "encoded-password", name, null, UserRole.USER);
		return em.persistAndFlush(user);
	}

	private Conversation persistConversation(User requester, User target) {
		User user1 = requester.getId().compareTo(target.getId()) < 0 ? requester : target;
		User user2 = requester.getId().compareTo(target.getId()) < 0 ? target : requester;
		Conversation conversation = new Conversation(user1, user2);
		return em.persistAndFlush(conversation);
	}

	private DirectMessage persistDirectMessage(Conversation conversation, User sender, User receiver, String content) {
		DirectMessage directMessage = new DirectMessage(conversation, sender, receiver, content);
		return em.persistAndFlush(directMessage);
	}
}