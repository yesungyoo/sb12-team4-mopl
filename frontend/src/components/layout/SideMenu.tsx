import {NavLink} from 'react-router-dom';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import useUIStore from '@/lib/stores/useUIStore';

import icTv from '@/assets/ic_tv.svg';
import icPlaylist from '@/assets/ic_playlist.svg';
import icProfileMenu from '@/assets/ic_profile_menu.svg';
import icPlane from '@/assets/ic_plane.svg';
import icSetting from '@/assets/ic_setting.svg';

interface NavItemProps {
  to: string;
  icon: string;
  label: string;
  collapsed: boolean;
}

function NavItem({ to, icon, label, collapsed }: NavItemProps) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'flex w-full items-center gap-2 rounded-full py-3 transition-all',
          collapsed ? 'justify-center px-0' : 'px-[14px]',
          isActive
            ? 'bg-gray-800 text-body2-b text-white'
            : 'text-body2-m text-gray-300 hover:bg-gray-800/30'
        )
      }
    >
      <img src={icon} alt="" className="size-[30px] shrink-0" />
      {!collapsed && <span>{label}</span>}
    </NavLink>
  );
}

export default function SideMenu() {
  const { data: jwt } = useAuthStore();
  const { sideMenuCollapsed } = useUIStore();

  const isAdmin = jwt?.userDto.role === 'ADMIN';

  return (
    <div
      className={cn(
        'shrink-0 border-r border-gray-800 transition-all duration-300',
        sideMenuCollapsed ? 'w-20' : 'w-60'
      )}
    >
      <nav
        className={cn(
          'flex flex-col gap-2 pb-10 pt-[30px] transition-all',
          sideMenuCollapsed ? 'px-[10px]' : 'px-[30px]'
        )}
      >
        <NavItem
          to="/contents"
          icon={icTv}
          label="콘텐츠 같이 보기"
          collapsed={sideMenuCollapsed}
        />
        <NavItem
          to="/playlists"
          icon={icPlaylist}
          label="플레이리스트"
          collapsed={sideMenuCollapsed}
        />
        <NavItem
          to="/profiles"
          icon={icProfileMenu}
          label="프로필"
          collapsed={sideMenuCollapsed}
        />
        <NavItem
          to="/conversations"
          icon={icPlane}
          label="메시지"
          collapsed={sideMenuCollapsed}
        />
        {isAdmin && (
          <NavItem
            to="/admin/users"
            icon={icSetting}
            label="사용자 관리"
            collapsed={sideMenuCollapsed}
          />
        )}
      </nav>
    </div>
  );
}
