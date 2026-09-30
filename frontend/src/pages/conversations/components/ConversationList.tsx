import { useEffect, useState } from 'react';
import { useInView } from 'react-intersection-observer';
import useConversationStore from '@/lib/stores/useConversationStore';
import { useSseStore } from '@/lib/stores/sseStore';
import { getConversationById } from '@/lib/api/conversations';
import ConversationItem from './ConversationItem';
import icSearch from '@/assets/ic_search.svg';
import type { DirectMessageDto } from '@/lib/types';

interface ConversationListProps {
  selectedConversationId?: string;
  onSelectConversation: (conversationId: string) => void;
}

export default function ConversationList({ selectedConversationId, onSelectConversation }: ConversationListProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const { data: conversations, loading, fetch, fetchMore, hasNext, updateParams } = useConversationStore();
  const { subscribe, unsubscribe } = useSseStore();

  // Infinite scroll sentinel
  const { ref: sentinelRef, inView } = useInView({
    threshold: 0,
    rootMargin: '100px',
  });

  // Initial fetch
  useEffect(() => {
    fetch();
  }, [fetch]);

  // Subscribe to direct messages SSE
  useEffect(() => {
    subscribe('direct-messages', async (message: DirectMessageDto) => {
      const { conversationId } = message;
      const currentConversations = useConversationStore.getState().data;

      // Check if conversation exists in current list
      const existingConversation = currentConversations.find(
        (conv) => conv.id === conversationId
      );

      if (existingConversation) {
        // Update existing conversation with new message
        useConversationStore.getState().update(conversationId, {
          lastestMessage: message,
          hasUnread: selectedConversationId !== conversationId, // Only mark unread if not selected
        });
      } else {
        // Fetch full conversation data and add to list
        try {
          const conversation = await getConversationById(conversationId);
          useConversationStore.getState().add(conversation);
        } catch (error) {
          console.error('Failed to fetch conversation:', error);
        }
      }
    });

    return () => {
      unsubscribe('direct-messages');
    };
  }, [subscribe, unsubscribe, selectedConversationId]);

  // Fetch more when sentinel is in view
  useEffect(() => {
    if (inView && hasNext() && !loading) {
      fetchMore();
    }
  }, [inView, hasNext, loading, fetchMore]);

  // Handle search
  const handleSearchChange = (value: string) => {
    setSearchQuery(value);
    updateParams({ keywordLike: value });
  };

  return (
    <div className="flex flex-col h-full">
      {/* Search Bar */}
      <div className="px-6 py-3.5 border-b border-gray-800">
        <div className="relative">
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => handleSearchChange(e.target.value)}
            placeholder="검색어를 입력하세요"
            className="w-full h-[42px] pl-5 pr-12 py-1 bg-gray-800/50 rounded-[50px] text-body3-m text-white placeholder:text-gray-400 focus:outline-none focus:ring-1 focus:ring-gray-700"
          />
          <div className="absolute right-4 top-1/2 -translate-y-1/2 w-6 h-6">
            <img src={icSearch} alt="search" className="w-full h-full" />
          </div>
        </div>
      </div>

      {/* Conversation List */}
      <div className="flex-1 overflow-y-auto scrollbar-thin scrollbar-thumb-gray-700 scrollbar-track-gray-900">
        {conversations.length === 0 && !loading ? (
          <div className="flex items-center justify-center h-full">
            <p className="text-body3-m text-gray-500">대화 목록이 없습니다.</p>
          </div>
        ) : (
          <>
            {conversations.map((conversation) => (
              <ConversationItem
                key={conversation.id}
                conversation={conversation}
                isSelected={selectedConversationId === conversation.id}
                onClick={() => onSelectConversation(conversation.id)}
              />
            ))}

            {/* Loading indicator */}
            {loading && (
              <div className="flex items-center justify-center py-4">
                <div className="w-6 h-6 border-2 border-gray-600 border-t-gray-300 rounded-full animate-spin" />
              </div>
            )}

            {/* Infinite scroll sentinel */}
            {hasNext() && !loading && <div ref={sentinelRef} className="h-px" />}
          </>
        )}
      </div>
    </div>
  );
}
