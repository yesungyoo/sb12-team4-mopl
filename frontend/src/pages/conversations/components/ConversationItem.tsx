import type { ConversationDto } from '@/lib/types';
import icProfileDefault from '@/assets/ic_profile_default.svg';

interface ConversationItemProps {
  conversation: ConversationDto;
  isSelected: boolean;
  onClick: () => void;
}

export default function ConversationItem({ conversation, isSelected, onClick }: ConversationItemProps) {
  // 시간 포맷팅 (예: "∙ 방금", "∙ 2분 전")
  const formatTime = (dateString: string) => {
    const now = new Date();
    const messageTime = new Date(dateString);
    const diffMs = now.getTime() - messageTime.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return '∙ 방금';
    if (diffMins < 60) return `∙ ${diffMins}분 전`;
    if (diffHours < 24) return `∙ ${diffHours}시간 전`;
    return `∙ ${diffDays}일 전`;
  };

  // 읽지 않은 메시지 여부
  const hasUnread = conversation.hasUnread;

  // 상대방 정보
  const otherUser = conversation.with;
  const displayName = otherUser?.name || 'User';

  return (
    <button
      onClick={onClick}
      className={`w-full flex items-center gap-3 px-6 py-5 hover:bg-white/5 transition-colors ${
        isSelected ? 'bg-white/10' : ''
      }`}
    >
      {/* Profile Avatar */}
      <div className="w-14 h-14 rounded-full flex-shrink-0 overflow-hidden">
        <img
          src={otherUser?.profileImageUrl || icProfileDefault}
          alt={`${displayName} profile`}
          className="w-full h-full object-cover"
        />
      </div>

      {/* Content */}
      <div className="flex-1 flex flex-col gap-1.5 min-w-0">
        {/* User Name */}
        <p className="text-body2-sb text-gray-400 truncate text-left">
          {displayName}
        </p>

        {/* Last Message & Time */}
        <div className="flex items-center justify-between gap-5">
          <div className="flex items-center gap-1 min-w-0">
            <p className={`text-body3-m truncate ${hasUnread ? 'text-gray-50' : 'text-gray-400'}`}>
              {conversation.lastestMessage?.content || '메시지 없음'}
            </p>
            <span className="text-caption1-m text-gray-500 whitespace-nowrap">
              {conversation.lastestMessage ? formatTime(conversation.lastestMessage.createdAt) : ''}
            </span>
          </div>

          {/* Unread Indicator */}
          {hasUnread && (
            <div className="w-2.5 h-2.5 rounded-full bg-red-notification flex-shrink-0" />
          )}
        </div>
      </div>
    </button>
  );
}
