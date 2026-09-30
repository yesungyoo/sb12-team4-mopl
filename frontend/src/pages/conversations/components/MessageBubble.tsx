import type { DirectMessageDto } from '@/lib/types';
import icProfileDefault from '@/assets/ic_profile_default.svg';
import {useNavigate} from "react-router-dom";

interface MessageBubbleProps {
  message: DirectMessageDto;
  isMine: boolean;
  showProfile?: boolean;
}

export default function MessageBubble({ message, isMine, showProfile = true }: MessageBubbleProps) {
  const navigate = useNavigate();
  // 시간 포맷팅 (예: "오전 1:00")
  const formatTime = (dateString: string) => {
    const date = new Date(dateString);
    const hours = date.getHours();
    const minutes = date.getMinutes();
    const period = hours < 12 ? '오전' : '오후';
    const displayHours = hours % 12 || 12;
    return `${period} ${displayHours}:${minutes.toString().padStart(2, '0')}`;
  };

  if (isMine) {
    // 내가 보낸 메시지 - 오른쪽 정렬, 핑크색
    return (
      <div className="flex items-end justify-end gap-1.5 px-[30px]">
        <div className="flex items-center px-0 py-1">
          <span className="text-caption1-m text-gray-600">
            {formatTime(message.createdAt)}
          </span>
        </div>
        <div className="bg-pink-700 px-3 py-1.5 rounded-tl-[20px] rounded-tr-[2px] rounded-bl-[20px] rounded-br-[20px] max-w-[600px]">
          <p className="text-body2-m-160 text-white break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  // 받은 메시지 - 왼쪽 정렬, 회색
  return (
    <div className="flex items-end gap-2.5 px-[30px]">
      {showProfile && (
        <div className="w-6 h-6 rounded-full flex-shrink-0 overflow-hidden cursor-pointer" onClick={() => navigate(`/profiles/${message.sender?.userId}`)}>
          <img
            src={message.sender?.profileImageUrl || icProfileDefault}
            alt={`${message.sender?.name || 'User'} profile`}
            className="w-full h-full object-cover"
          />
        </div>
      )}
      <div className="flex items-end gap-1.5">
        <div className="bg-gray-800 px-3 py-1.5 rounded-tl-[20px] rounded-tr-[20px] rounded-bl-[2px] rounded-br-[20px] max-w-[600px]">
          <p className="text-body2-m-160 text-white break-words">{message.content}</p>
        </div>
        <div className="flex items-center px-0 py-1">
          <span className="text-caption1-m text-gray-600">
            {formatTime(message.createdAt)}
          </span>
        </div>
      </div>
    </div>
  );
}
