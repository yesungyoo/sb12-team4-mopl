import { Client, type StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { create } from 'zustand';
import { toast } from 'sonner';

interface ChatRestriction {
  restrictionLevel: 'TEMPORARY_SHORT' | 'TEMPORARY_LONG';
  restrictedUntil: string;
}

let restrictionTimer: ReturnType<typeof setTimeout> | undefined;
export function formatRestrictionEnd(until: string) {
  const date = new Date(until);
  const today = new Date();
  return date.toLocaleString('ko-KR', {
    ...(date.toDateString() !== today.toDateString() ? { month: '2-digit', day: '2-digit' } : {}),
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

interface WebSocketState {
  restriction: ChatRestriction | null;
  handleModerationError: (payload: unknown) => void;
  stompClient: Client | null;
  isConnected: boolean;
  isConnecting: boolean;
  subscriptions: Map<string, StompSubscription>;
  connect: (accessToken: string) => Promise<void>;
  disconnect: () => void;
  subscribe: (destination: string, callback: (message: any) => void) => void;
  unsubscribe: (destination: string) => void;
  send: (destination: string, body: any) => void;
}

export const useWebSocketStore = create<WebSocketState>((set, get) => ({
  restriction: null,
  handleModerationError: (payload: unknown) => {
    if (!payload || typeof payload !== 'object') return;
    const error = payload as Record<string, unknown>;
    if (error.code !== 'CHAT_RESTRICTED') {
      if (error.code === 'CHAT_RESTRICTION_CHECK_FAILED') {
        toast.error('채팅 상태를 확인하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        return;
      }
      if (typeof error.message === 'string') toast.error(error.message);
      return;
    }
    const until = typeof error.restrictedUntil === 'string' ? Date.parse(error.restrictedUntil) : NaN;
    if (!Number.isFinite(until)) {
      toast.error('반복적인 메시지 이용 정책 위반으로 채팅 이용이 일시적으로 제한되었습니다.');
      return;
    }
    // 만료된 응답은 제한 UI를 다시 만들지 않는다.
    if (until <= Date.now()) return;
    if (error.restrictionLevel !== 'TEMPORARY_SHORT' && error.restrictionLevel !== 'TEMPORARY_LONG') return;
    const current = get().restriction;
    if (current && Date.parse(current.restrictedUntil) >= until) return;
    const restriction: ChatRestriction = {
      restrictionLevel: error.restrictionLevel, restrictedUntil: error.restrictedUntil as string,
    };
    if (restrictionTimer !== undefined) clearTimeout(restrictionTimer);
    set({ restriction });
    const clearWhenExpired = () => {
      if (get().restriction !== restriction) return;
      const remaining = until - Date.now();
      if (remaining > 0) restrictionTimer = setTimeout(clearWhenExpired, Math.min(remaining, 2147483647));
      else { restrictionTimer = undefined; set({ restriction: null }); }
    };
    clearWhenExpired();
    toast.error(`반복적인 메시지 이용 정책 위반으로 채팅 이용이 일시적으로 제한되었습니다. ${formatRestrictionEnd(restriction.restrictedUntil)}까지 메시지를 보낼 수 없습니다.`);
  },
  stompClient: null,
  isConnected: false,
  isConnecting: false,
  subscriptions: new Map(),

  connect: async (accessToken: string) => {
    // 재연결은 기존 Client가 담당하며 화면의 connect 호출은 중복 생성하지 않는다.
    if (get().stompClient) return;

    const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
    const client = new Client({
      webSocketFactory: () => new SockJS(`${BASE_URL}/ws`),
      connectHeaders: {
        Authorization: `Bearer ${accessToken}`,
      },
      // STOMP 프레임에 Authorization 헤더가 포함될 수 있어 debug 출력을 끈다.
      debug: () => {},
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    const isCurrent = () => get().stompClient === client;
    client.onConnect = () => {
      if (!isCurrent()) return;
      // 재연결에서도 사용자 전용 오류를 방 구독보다 먼저 등록한다.
      const errors = client.subscribe('/user/sub/errors', (message) => {
        if (!isCurrent() || !get().isConnected) return;
        try { get().handleModerationError(JSON.parse(message.body)); }
        catch { toast.error('메시지 전송 오류를 확인할 수 없습니다.'); }
      });
      set({ stompClient: client, isConnected: true, isConnecting: false,
        subscriptions: new Map([['/user/sub/errors', errors]]) });
      console.log('WebSocket 연결 성공');
    };

    client.onStompError = (frame) => {
      if (!isCurrent()) return;
      console.error('STOMP 에러:', frame);
      set({ isConnected: false, isConnecting: true, subscriptions: new Map() });
    };

    client.onWebSocketClose = () => {
      if (!isCurrent()) return;
      set({ isConnected: false, isConnecting: true, subscriptions: new Map() });
    };

    set({ stompClient: client, isConnecting: true });
    client.activate();
  },

  disconnect: () => {
    const { stompClient } = get();
    if (restrictionTimer !== undefined) clearTimeout(restrictionTimer);
    restrictionTimer = undefined;
    set({ stompClient: null, isConnected: false, isConnecting: false, subscriptions: new Map(), restriction: null });
    // 상태를 먼저 비워 이전 연결의 지연된 callback을 무시한다.
    if (stompClient) void stompClient.deactivate();
  },

  subscribe: (destination: string, callback: <T>(message: T) => void) => {
    const { stompClient, isConnected, subscriptions } = get();

    if (subscriptions.has(destination) || !isConnected || stompClient == null) {
      return;
    }

    const subscription: StompSubscription = stompClient.subscribe(destination, (message) => {
      if (get().stompClient !== stompClient || !get().isConnected) return;
      const payload = JSON.parse(message.body);
      callback(payload);
    });

    subscriptions.set(destination, subscription);
  },

  unsubscribe: (destination: string) => {
    const { stompClient, isConnected, subscriptions } = get();

    if (!subscriptions.has(destination) || !isConnected || stompClient == null) {
      return;
    }

    const subscription = subscriptions.get(destination);
    if (subscription) {
      subscription.unsubscribe();
      subscriptions.delete(destination);
      set({ subscriptions });
    }
  },

  send: (destination: string, body: any) => {
    const { stompClient, isConnected, restriction } = get();
    const isMessage = /^\/pub\/(contents\/[^/]+\/chat|conversations\/[^/]+\/direct-messages)$/.test(destination);
    if (isMessage && restriction) {
      if (Date.parse(restriction.restrictedUntil) > Date.now()) {
        toast.error(`반복적인 메시지 이용 정책 위반으로 채팅 이용이 일시적으로 제한되었습니다. ${formatRestrictionEnd(restriction.restrictedUntil)}까지 메시지를 보낼 수 없습니다.`);
        return;
      }
      set({ restriction: null });
    }

    if (stompClient && isConnected) {
      stompClient.publish({
        destination,
        body: JSON.stringify(body),
      });
    } else {
      console.error('WebSocket이 연결되어 있지 않습니다.');
    }
  },
}));
