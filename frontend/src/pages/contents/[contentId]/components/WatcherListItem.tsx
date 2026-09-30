import icProfileDefault from '@/assets/ic_profile_default.svg';
import type { UserSummary } from '@/lib/types';
import { useNavigate } from 'react-router-dom';

interface WatcherListItemProps {
  user: UserSummary;
}

export default function WatcherListItem({ user }: WatcherListItemProps) {
  const navigate = useNavigate();

  const handleNameClick = () => {
    navigate(`/profiles/${user.userId}`);
  };

  return (
    <div className="flex items-center gap-1.5 py-2 w-full">
      {/* 프로필 아바타 */}
      <div className="relative w-[18px] h-[18px] border border-white/10 rounded-full overflow-hidden shrink-0">
        <img
          src={user.profileImageUrl || icProfileDefault}
          alt=""
          className="w-full h-full object-cover"
        />
      </div>

      {/* 사용자 이름 */}
      <span
        className="text-body3-m text-gray-300 whitespace-nowrap overflow-hidden text-ellipsis shrink cursor-pointer hover:text-gray-100 transition-colors"
        onClick={handleNameClick}
      >
        {user.name}
      </span>
    </div>
  );
}
