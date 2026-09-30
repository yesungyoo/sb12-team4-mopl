import { useEffect, useRef } from 'react';
import useDirectMessageStore from '@/lib/stores/useDirectMessageStore';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import MessageBubble from './MessageBubble';
import MessageInput from './MessageInput';
import icProfileDefault from '@/assets/ic_profile_default.svg';
import useConversationDetailStore from "@/lib/stores/useConversationDetailStore.ts";
import {markDirectMessageAsRead} from "@/lib/api";
import useConversationStore from "@/lib/stores/useConversationStore.ts";
import {useNavigate} from "react-router-dom";

interface MessageThreadProps {
  conversationId: string;
  onSendMessage: (content: string) => void;
  isConnected: boolean;
}

export default function MessageThread({ conversationId, onSendMessage, isConnected }: MessageThreadProps) {
  const { data: conversation, updateParams: updateConversationDetailParam, clearData: clearConversationDetailData } = useConversationDetailStore();
  const { data: messages, loading, updateParams, clearData, fetchMore, hasNext } = useDirectMessageStore();
  const { data: authentication } = useAuthStore();
  const { update: updateConversation } = useConversationStore();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const isLoadingMoreRef = useRef(false);
  const navigate = useNavigate();

  // Fetch messages when conversation changes

  useEffect(() => {
    if (conversationId) {
      updateConversationDetailParam({conversationId});
      updateParams({ conversationId });
    }

    return () => {
      clearConversationDetailData();
      clearData();
    };
  }, [conversationId, updateParams, clearData, updateConversationDetailParam, clearConversationDetailData]);

  useEffect(() => {
    if (conversation && conversation.lastestMessage) {
      markDirectMessageAsRead(conversation.id, conversation.lastestMessage.id);
      updateConversation(conversation.id, { hasUnread: false });
    }
  }, [conversation]);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (messagesEndRef.current && !isLoadingMoreRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages]);

  // Handle scroll to load more messages
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleScroll = async () => {
      const { scrollTop } = container;

      // Check if scrolled to top (with 50px threshold)
      if (scrollTop < 50 && hasNext() && !loading && !isLoadingMoreRef.current) {
        isLoadingMoreRef.current = true;

        // Store current scroll height before loading more
        const previousScrollHeight = container.scrollHeight;

        try {
          await fetchMore();

          // Restore scroll position after new messages are loaded
          // This prevents the view from jumping to the top
          requestAnimationFrame(() => {
            const newScrollHeight = container.scrollHeight;
            const heightDifference = newScrollHeight - previousScrollHeight;
            container.scrollTop = scrollTop + heightDifference;
            isLoadingMoreRef.current = false;
          });
        } catch (error) {
          console.error('Failed to load more messages:', error);
          isLoadingMoreRef.current = false;
        }
      }
    };

    container.addEventListener('scroll', handleScroll);
    return () => container.removeEventListener('scroll', handleScroll);
  }, [hasNext, loading, fetchMore]);

  // Group messages by date for date separators
  const formatDateSeparator = (dateString: string) => {
    const date = new Date(dateString);
    const year = date.getFullYear().toString().slice(2); // 24
    const month = date.getMonth() + 1; // 2
    const day = date.getDate(); // 11
    const hours = date.getHours();
    const minutes = date.getMinutes();
    const period = hours < 12 ? '오전' : '오후';
    const displayHours = hours % 12 || 12;

    return `${year}. ${month}. ${day}. ${period} ${displayHours}:${minutes.toString().padStart(2, '0')}`;
  };

  // Check if message should show profile (first message from user in sequence)
  const shouldShowProfile = (index: number, messagesArray: typeof messages): boolean => {
    if (index === 0) return true;
    const currentMessage = messagesArray[index];
    const previousMessage = messagesArray[index - 1];
    return currentMessage.sender.userId !== previousMessage.sender.userId;
  };

  // Get other user info (temporary - assumes first message sender who isn't me)
  const currentUserId = authentication?.userDto.id;
  const otherUser = conversation?.with;
  const otherUserName = otherUser?.name;

  // Reverse messages for display (server returns newest first, but UI shows oldest first)
  const displayMessages = messages.slice().reverse();

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-[30px] py-5 border-b border-gray-800">
        <div className="flex items-center gap-2.5">
          {/* Profile */}
          <div className="w-7 h-7 rounded-full flex-shrink-0 overflow-hidden">
            <img
              src={otherUser?.profileImageUrl || icProfileDefault}
              alt={`${otherUserName || 'User'} profile`}
              className="w-full h-full object-cover"
            />
          </div>

          {/* Name */}
          <h2
              className="text-title1-sb text-white cursor-pointer hover:font-bold tracking-normal"
              onClick={() => navigate(`/profiles/${otherUser?.userId}`)}
          >{otherUserName}</h2>
        </div>
      </div>

      {/* Messages Area */}
      <div
        ref={containerRef}
        className="flex-1 overflow-y-auto scrollbar-thin scrollbar-thumb-gray-700 scrollbar-track-gray-900"
      >
        {loading && displayMessages.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <div className="w-8 h-8 border-2 border-gray-600 border-t-gray-300 rounded-full animate-spin" />
          </div>
        ) : displayMessages.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <p className="text-body2-m text-gray-500">메시지가 없습니다.</p>
          </div>
        ) : (
          <div className="flex flex-col gap-5 py-[30px]">
            {/* Loading indicator for infinite scroll */}
            {loading && displayMessages.length > 0 && (
              <div className="flex justify-center py-2">
                <div className="w-6 h-6 border-2 border-gray-600 border-t-gray-300 rounded-full animate-spin" />
              </div>
            )}

            {/* Date separator - Show for first message (oldest message) */}
            {displayMessages.length > 0 && (
              <div className="flex justify-center">
                <p className="text-body3-m text-gray-500">
                  {formatDateSeparator(displayMessages[0].createdAt)}
                </p>
              </div>
            )}

            {/* Messages */}
            <div className="flex flex-col gap-3">
              {displayMessages.map((message, index) => (
                <MessageBubble
                  key={message.id}
                  message={message}
                  isMine={message.sender.userId === currentUserId}
                  showProfile={shouldShowProfile(index, displayMessages)}
                />
              ))}
              <div ref={messagesEndRef} />
            </div>
          </div>
        )}
      </div>

      {/* Input Area */}
      <MessageInput onSend={onSendMessage} disabled={!isConnected} />
    </div>
  );
}
