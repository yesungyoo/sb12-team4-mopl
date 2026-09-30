import { create } from 'zustand';
import type { ContentChatDto } from '@/lib/types';

interface ChatMessageState {
  messages: ContentChatDto[];
  loading: boolean;
  error?: string;
}

interface ChatMessageActions {
  addMessage: (message: ContentChatDto) => void;
  setMessages: (messages: ContentChatDto[]) => void;
  clearMessages: () => void;
  clearError: () => void;
}

type ChatMessageStore = ChatMessageState & ChatMessageActions;

const useChatMessageStore = create<ChatMessageStore>((set) => ({
  messages: [],
  loading: false,
  error: undefined,

  addMessage: (message) =>
    set((state) => ({
      messages: [...state.messages, message],
    })),

  setMessages: (messages) =>
    set({
      messages,
      loading: false,
      error: undefined,
    }),

  clearMessages: () =>
    set({
      messages: [],
      error: undefined,
    }),

  clearError: () =>
    set({
      error: undefined,
    }),
}));

export default useChatMessageStore;
