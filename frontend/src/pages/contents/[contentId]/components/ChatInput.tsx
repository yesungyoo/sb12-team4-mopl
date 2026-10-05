import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { useWebSocketStore, formatRestrictionEnd } from '@/lib/stores/websocketStore';

interface ChatInputProps {
  onSendMessage: (message: string) => void;
  disabled?: boolean;
}

export default function ChatInput({ onSendMessage, disabled }: ChatInputProps) {
  const [message, setMessage] = useState('');
  const restriction = useWebSocketStore((state) => state.restriction);
  const isRestricted = !!restriction && Date.parse(restriction.restrictedUntil) > Date.now();

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (message.trim() && !disabled && !isRestricted) {
      onSendMessage(message.trim());
      setMessage('');
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="w-full">
      {isRestricted && restriction && (
        <p role="status" className="mb-2 text-body3-m text-pink-400">
          메시지 이용 정책 위반 · 채팅 이용 제한 중 · {formatRestrictionEnd(restriction.restrictedUntil)}까지
        </p>
      )}
      <div className="relative w-full h-[54px] bg-gray-800/50 border-[1.5px] border-gray-800 rounded-xl overflow-hidden">
        <input
          type="text"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="채팅을 입력해주세요"
          disabled={disabled || isRestricted}
          className="w-full h-full px-5 py-3.5 text-body2-m-140 text-gray-50 placeholder:text-gray-400 bg-transparent border-none outline-none focus:ring-0 disabled:opacity-50"
        />
      </div>
    </form>
  );
}
