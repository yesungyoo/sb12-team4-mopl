import {useNavigate, useParams} from 'react-router-dom';
import UserProfileSection from "@/pages/profiles/[userId]/components/UserProfileSection.tsx";
import OwnedPlaylistsSection from "@/pages/profiles/[userId]/components/OwnedPlaylistsSection.tsx";
import SubscribedPlaylistSection
  from "@/pages/profiles/[userId]/components/SubscribedPlaylistSection.tsx";
import {Button} from "@/components/ui/button.tsx";


export default function ProfilePage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();

  // Error state
  if (!userId) {
    return (
        <div className="flex flex-col items-center justify-center min-h-screen gap-4">
          <p className="text-body1-m text-gray-300">{'사용자 ID를 알 수 없습니다.'}</p>
          <Button onClick={() => navigate(-1)}>뒤로 가기</Button>
        </div>
    );
  }

  return (
    <div className="w-full min-h-screen bg-background">
      <div className="max-w-[1680px] mx-auto px-[70px] py-[60px]">
        <UserProfileSection userId={userId}/>
        <OwnedPlaylistsSection userId={userId}/>
        <SubscribedPlaylistSection userId={userId}/>
      </div>
    </div>
  );
}
