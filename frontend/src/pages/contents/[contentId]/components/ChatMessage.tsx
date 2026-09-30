import icProfileDefault from '@/assets/ic_profile_default.svg';
import type { ContentChatDto } from '@/lib/types';
import {useNavigate} from "react-router-dom";

interface ChatMessageProps {
  message: ContentChatDto;
}

export default function ChatMessage({ message }: ChatMessageProps) {
  const navigate = useNavigate();
  const handleNameClick = (userId: string) => {
    navigate(`/profiles/${userId}`);
  };

  return (
    <div className="flex gap-4 items-start py-1">
      {/* 프로필 아바타 */}
      <div className="flex gap-1.5 items-center shrink-0">
        <div className="relative w-5 h-5 border border-white/10 rounded-full overflow-hidden">
          <img
            src={message.sender.profileImageUrl || icProfileDefault}
            alt=""
            className="w-full h-full object-cover"
          />
        </div>

        {/* 사용자 이름 */}
        <div className="w-[100px] overflow-hidden text-ellipsis" onClick={() => handleNameClick(message.sender.userId)}>
          <span className="text-body2-sb text-gray-400 whitespace-nowrap overflow-hidden text-ellipsis cursor-pointer hover:text-gray-100 transition-colors">
            {message.sender.name}
          </span>
        </div>
      </div>

      {/* 메시지 내용 */}
      <div className="flex-1">
        <p className="text-body2-m-140 text-gray-50 whitespace-pre-wrap break-words">
          {message.content}
        </p>
      </div>
    </div>
  );
}
