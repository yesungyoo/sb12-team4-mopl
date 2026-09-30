import { Outlet } from 'react-router-dom';
import GNB from '@/components/layout/GNB';
import SideMenu from '@/components/layout/SideMenu';

/**
 * ProtectedLayout - 보호된 라우트용 레이아웃
 */
export default function ProtectedLayout() {

  return (
    <div className="min-h-screen bg-background">
      <GNB />
      <div className="flex">
        <SideMenu />
        <main className="flex-1">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
