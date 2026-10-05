import { Outlet } from 'react-router-dom';

import GNB from '@/components/layout/GNB';
import SideMenu from '@/components/layout/SideMenu';
import AiPlaylist from '@/features/ai-playlist/AiPlaylist';

export default function ProtectedLayout() {
  return (
    <div className="min-h-screen w-full overflow-x-hidden bg-background">
      <GNB />

      <div className="flex w-full min-w-0">
        <SideMenu />

        <main className="min-w-0 flex-1 overflow-x-hidden">
          <Outlet />
        </main>
      </div>

      <AiPlaylist />
    </div>
  );
}