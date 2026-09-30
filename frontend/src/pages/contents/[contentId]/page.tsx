import { useEffect, useState, useRef } from 'react';
import { useParams } from 'react-router-dom';
import useContentDetailStore from '@/lib/stores/useContentDetailStore';
import useWatchingSessionStore from '@/lib/stores/useWatchingSessionStore';
import useChatMessageStore from '@/lib/stores/useChatMessageStore';
import {useWebSocketStore} from '@/lib/stores/websocketStore';
import {useAuthStore} from '@/lib/stores/useAuthStore';
import ContentInfo from './components/ContentInfo';
import ChatMessage from './components/ChatMessage';
import ChatInput from './components/ChatInput';
import WatcherListItem from './components/WatcherListItem';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import type {ContentChatDto, WatchingSessionChange} from "@/lib/types";

export default function ContentDetailPage() {
  const { contentId } = useParams<{ contentId: string }>();
  const [isConnecting, setIsConnecting] = useState(false);
  const chatContainerRef = useRef<HTMLDivElement>(null);

  // Stores
  const { data: content, loading: contentLoading, updateParams: updateContentParams, clear: clearContent } = useContentDetailStore();
  const { data: watchingSessions, fetch: fetchWatchingSessions, updateParams: updateWatchingSessionParams, add: addWatchingSession, delete: removeWatchingSession } = useWatchingSessionStore();
  const { messages, addMessage, clearMessages } = useChatMessageStore();
  const { connect, subscribe, unsubscribe, isConnected, send } = useWebSocketStore();
  const { data: authentication } = useAuthStore();

  // 콘텐츠 상세 페칭
  useEffect(() => {
    if (contentId) {
      updateContentParams({ contentId });
    }

    return () => clearContent(); // Cleanup on unmount
  }, [contentId, updateContentParams, clearContent]);

  // 시청자 목록 페칭
  useEffect(() => {
    if (contentId) {
      updateWatchingSessionParams({ contentId });
    }
  }, [contentId, updateWatchingSessionParams]);

  // WebSocket 연결 및 구독
  useEffect(() => {
    if (!contentId || !authentication) return;
    const accessToken = authentication.accessToken;

    const setupWebSocket = async () => {
      setIsConnecting(true);

      try {
        // 1. WebSocket이 연결되어 있지 않으면 연결
        if (!isConnected) {
          await connect(accessToken);
        }

        subscribe(`/sub/contents/${contentId}/watch`, (watchingSessionChange: WatchingSessionChange) => {
          // 시청자 입장/퇴장 이벤트 처리
          if (watchingSessionChange.type === 'JOIN') {
            addWatchingSession(watchingSessionChange.watchingSession);
          } else if (watchingSessionChange.type === 'LEAVE') {
            removeWatchingSession(watchingSessionChange.watchingSession.id);
          }
        });

        subscribe(`/sub/contents/${contentId}/chat`, (message: ContentChatDto) => {
          addMessage(message);
        });
      } catch (error) {
        console.error('WebSocket setup failed:', error);
      } finally {
        setIsConnecting(false);
      }
    };

    setupWebSocket();

    // 페이지 이탈 시 구독 해제
    return () => {
      unsubscribe(`/sub/contents/${contentId}/watch`);
      unsubscribe(`/sub/contents/${contentId}/chat`);
      clearMessages();
    };
  }, [contentId, authentication, isConnected, connect, subscribe, unsubscribe, addMessage, clearMessages, fetchWatchingSessions]);

  // 채팅 메시지 전송 핸들러
  const handleSendMessage = (message: string) => {
    if (!contentId) return;

    try {
      send(`/pub/contents/${contentId}/chat`, {
        content: message,
      });
    } catch (error) {
      console.error('Failed to send message:', error);
    }

    console.log('Send message:', message, 'to', `/pub/contents/${contentId}/chat`);
  };

  // 채팅 스크롤 자동 하단 이동
  useEffect(() => {
    if (chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [messages]);

  // 로딩 상태
  if (contentLoading || isConnecting) {
    return (
      <div className="flex items-center justify-center h-screen">
        <LoadingSpinner />
      </div>
    );
  }

  if (!content) {
    return (
      <div className="flex items-center justify-center h-screen">
        <p className="text-body2-m text-gray-500">콘텐츠를 찾을 수 없습니다.</p>
      </div>
    );
  }

  // 시청자 목록 (중복 제거)
  const watchers = watchingSessions
    .map((session) => session.watcher)
    .filter((watcher, index, self) => self.findIndex((w) => w.userId === watcher.userId) === index);

  return (
    <div className="flex flex-col lg:flex-row w-full h-full bg-background">
      {/* 좌측: 콘텐츠 정보 - 비율 기반 (약 30%) */}
      <div className="w-full lg:w-[30%] lg:min-w-[400px] lg:max-w-[526px] h-auto lg:h-full overflow-y-auto shrink-0">
        <div className="p-8 lg:pl-[50px] lg:pr-[50px] lg:pt-20 lg:pb-20">
          <ContentInfo content={content}/>
        </div>
      </div>

      {/* 중앙: 실시간 채팅 - 가변 너비 (남은 공간 차지) */}
      <div className="flex-1 min-w-0 h-auto lg:h-full flex items-stretch justify-center p-4 lg:py-20 lg:px-8">
        <div className="w-full h-full flex flex-col backdrop-blur-[25px] bg-[rgba(46,46,56,0.4)] border border-[#212126] rounded-2xl overflow-hidden">
          {/* 채팅 헤더 */}
          <div className="px-10 pt-[30px] pb-[10px]">
            <div className="flex items-center gap-1.5">
              <h2 className="text-title1-b text-gray-50">실시간 채팅</h2>
              <div className="flex items-center gap-1">
                <div className="w-4 h-4 rounded-full bg-pink-500" />
                <span className="text-body3-b text-gray-400">
                  {watchers.length.toLocaleString()}
                </span>
              </div>
            </div>
          </div>

          {/* 채팅 메시지 영역 */}
          <div ref={chatContainerRef} className="flex-1 overflow-y-auto px-10 py-[10px]">
            {messages.length === 0 ? (
              <div className="flex items-center justify-center h-full">
                <p className="text-body2-m text-gray-500">
                  채팅 메시지가 없습니다.
                </p>
              </div>
            ) : (
              <div className="space-y-[10px]">
                {messages.map((message, index) => (
                  <ChatMessage key={index} message={message} />
                ))}
              </div>
            )}
          </div>

          {/* 채팅 입력 */}
          <div className="px-10 py-[10px]">
            <ChatInput onSendMessage={handleSendMessage} disabled={!isConnected} />
          </div>
        </div>
      </div>

      {/* 우측: 시청자 목록 - 비율 기반 (약 15-20%) */}
      <div className="w-full lg:w-[18%] lg:min-w-[220px] lg:max-w-[270px] h-auto lg:h-full overflow-y-auto lg:border-l border-t lg:border-t-0 border-gray-800 shrink-0">
        <div className="px-[30px] py-5">
          {/* 헤더 */}
          <div className="py-2 mb-1.5">
            <h3 className="text-body3-b text-gray-300">현재 시청자 목록</h3>
          </div>

          {/* 시청자 리스트 */}
          <div className="space-y-0">
            {watchers.length === 0 ? (
              <p className="text-body3-m text-gray-500 py-2">시청자가 없습니다.</p>
            ) : (
              watchers.map((watcher) => <WatcherListItem key={watcher.userId} user={watcher} />)
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
