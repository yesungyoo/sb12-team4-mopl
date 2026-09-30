import { useState, type KeyboardEvent } from 'react';

interface MessageInputProps {
  onSend: (message: string) => void;
  disabled?: boolean;
}

export default function MessageInput({ onSend, disabled = false }: MessageInputProps) {
  const [message, setMessage] = useState('');

  const handleSend = () => {
    if (!message.trim() || disabled) return;
    onSend(message.trim());
    setMessage('');
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    // Korean IME composition 지원 - Enter 키 처리 시 composition 중이면 무시
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="w-full px-[30px] py-[30px]">
      <div className="relative">
        <input
          type="text"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="메시지 입력..."
          disabled={disabled}
          className="w-full h-[54px] px-5 py-3.5 bg-gray-800/50 border-[1.5px] border-gray-800 rounded-xl text-body2-m-140 text-white placeholder:text-gray-400 focus:outline-none focus:border-gray-700 disabled:opacity-50 disabled:cursor-not-allowed"
        />
      </div>
    </div>
  );
}
