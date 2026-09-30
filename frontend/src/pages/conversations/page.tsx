import { useEffect, useState } from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import { useWebSocketStore } from '@/lib/stores/websocketStore';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import useDirectMessageStore from '@/lib/stores/useDirectMessageStore';
import ConversationList from './components/ConversationList';
import MessageThread from './components/MessageThread';
import EmptyState from './components/EmptyState';
import type { DirectMessageDto } from '@/lib/types';
import {markDirectMessageAsRead} from "@/lib/api";
import useConversationStore from "@/lib/stores/useConversationStore.ts";

export default function ConversationsPage() {
  const navigate = useNavigate();
  const { conversationId: selectedConversationId  } = useParams<{ conversationId: string }>();
  const [isConnecting, setIsConnecting] = useState(false);
  const {update: updateConversation} = useConversationStore();

  // Stores
  const { connect, subscribe, unsubscribe, isConnected, send } = useWebSocketStore();
  const { data: authentication } = useAuthStore();

  // WebSocket connection and subscription
  useEffect(() => {
    if (!selectedConversationId || !authentication) return;

    const accessToken = authentication.accessToken;

    const setupWebSocket = async () => {
      setIsConnecting(true);

      try {
        // Connect if not already connected
        if (!isConnected) {
          await connect(accessToken);
        }

        // Subscribe to conversation-specific message channel
        subscribe(`/sub/conversations/${selectedConversationId}/direct-messages`, (message: DirectMessageDto) => {
          // Add new message to store
          useDirectMessageStore.getState().add(message);
          markDirectMessageAsRead(selectedConversationId, message.id);
          updateConversation(selectedConversationId, {lastestMessage: message, hasUnread: false});
        });
      } catch (error) {
        console.error('WebSocket setup failed:', error);
      } finally {
        setIsConnecting(false);
      }
    };

    setupWebSocket();

    // Cleanup on unmount or conversation change
    return () => {
      unsubscribe(`/sub/conversations/${selectedConversationId}/direct-messages`);
    };
  }, [selectedConversationId, authentication, isConnected, connect, subscribe, unsubscribe]);

  // Handle conversation selection
  const handleSelectConversation = (conversationId: string) => {
    navigate(`/conversations/${conversationId}`);
  };

  // Handle sending message
  const handleSendMessage = (content: string) => {
    if (!selectedConversationId) return;

    try {
      send(`/pub/conversations/${selectedConversationId}/direct-messages`, {
        content,
      });
    } catch (error) {
      console.error('Failed to send message:', error);
    }
  };

  return (
    <div className="flex w-full h-[calc(100vh-80px)] bg-background">
      {/* Left Panel: Conversation List */}
      <div className="w-[400px] border-r border-gray-800 flex flex-col">
        <ConversationList
          selectedConversationId={selectedConversationId}
          onSelectConversation={handleSelectConversation}
        />
      </div>

      {/* Right Panel: Message Thread or Empty State */}
      <div className="flex-1">
        {selectedConversationId ? (
          <MessageThread
            conversationId={selectedConversationId}
            onSendMessage={handleSendMessage}
            isConnected={isConnected && !isConnecting}
          />
        ) : (
          <EmptyState />
        )}
      </div>
    </div>
  );
}
