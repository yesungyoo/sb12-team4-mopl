import {useEffect, useState} from 'react';
import icHamburger from '@/assets/ic_hamburger.svg';
import Logo from '@/assets/Logo.svg';
import icPlus from '@/assets/ic_plus.svg';
import icBell from '@/assets/ic_bell.svg';
import icProfileDefault from '@/assets/ic_profile_default.svg';
import useUIStore from '@/lib/stores/useUIStore';
import {useNavigate} from "react-router-dom";
import {useAuthStore} from "@/lib/stores/useAuthStore.ts";
import useNotificationStore from '@/lib/stores/useNotificationStore';
import { useSseStore } from '@/lib/stores/sseStore';
import NotificationDropdown from './NotificationDropdown';
import ContentFormDialog from '@/pages/contents/components/ContentFormDialog';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import type { NotificationDto } from '@/lib/types';

export default function GNB() {
  const { toggleSideMenu } = useUIStore();
  const navigate = useNavigate();
  const { data: authentication, signOut } = useAuthStore();
  const { count, fetch: fetchNotifications, clear: clearNotifications } = useNotificationStore();
  const { connect, subscribe, unsubscribe, isConnected } = useSseStore();
  const [isNotificationOpen, setIsNotificationOpen] = useState(false);
  const [isContentFormOpen, setIsContentFormOpen] = useState(false);

  useEffect(() => {
    fetchNotifications();

    return () => {
      clearNotifications();
    }
  }, []);

  useEffect(() => {
    if (!authentication?.accessToken) return;

    const setupSSE = async () => {
      // Connect to SSE if not already connected
      if (!isConnected) {
        await connect(authentication.accessToken);
      }

      // Subscribe to "notifications" topic
      subscribe('notifications', (newNotification: NotificationDto) => {
        // Add new notification to store
        useNotificationStore.getState().add(newNotification);
      });
    };

    setupSSE();

    // Cleanup: unsubscribe on unmount
    return () => {
      unsubscribe('notifications');
    };
  }, [authentication, isConnected, connect, subscribe, unsubscribe]);

  const handleLogout = async () => {
    await signOut();
    navigate('/sign-in');
  };

  const toggleNotification = () => {
    setIsNotificationOpen((prev) => !prev);
  };

  return (
    <div className="h-20 border-b border-gray-800">
      <div className="flex h-full items-center justify-between px-[42px] py-[21px]">
        {/* Left Section */}
        <div className="flex items-center gap-9">
          <button
            type="button"
            className="size-6 overflow-hidden"
            aria-label="Toggle menu"
            onClick={toggleSideMenu}
          >
            <img src={icHamburger} alt="" className="size-full" />
          </button>
          <div className="size-9 overflow-hidden" onClick={() => navigate('/')}>
            <img src={Logo} alt="Logo" className="size-full" />
          </div>
        </div>

        {/* Right Section */}
        <div className="flex items-center gap-9">
          {
            authentication?.userDto.role === 'ADMIN' &&
              (
                  <button
                      type="button"
                      onClick={() => setIsContentFormOpen(true)}
                      className="flex items-center gap-1 rounded-full bg-gray-800/50 px-[18px] py-2.5 text-body2-sb text-gray-300"
                  >
                    <img src={icPlus} alt="" className="size-5" />
                    <span>콘텐츠 등록</span>
                  </button>
              )
          }


          <div className="flex items-center gap-9">
            {/* Notification Bell with Badge */}
            <div className="relative">
              <button
                type="button"
                className="relative size-6"
                aria-label="Notifications"
                onClick={toggleNotification}
              >
                <img src={icBell} alt="" className="size-full" />
                {count() > 0 && (
                  <div className="absolute -right-0.5 -top-0.5 flex size-4 items-center justify-center rounded-full bg-red-notification">
                    <span className="text-[9px] font-bold leading-none text-gray-100">
                      {count() > 99 ? '99+' : count()}
                    </span>
                  </div>
                )}
              </button>

              {/* Notification Dropdown */}
              {isNotificationOpen && (
                <NotificationDropdown onClose={() => setIsNotificationOpen(false)} />
              )}
            </div>

            {/* Profile Avatar */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="relative size-6 overflow-hidden rounded-full border border-white/10 bg-gray-600"
                  aria-label="Profile"
                >
                  <img
                    src={authentication?.userDto.profileImageUrl || icProfileDefault}
                    alt={authentication?.userDto.name}
                    className="size-full"
                  />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent
                align="end"
                className="bg-gray-800 border-gray-700 min-w-[120px]"
              >
                <DropdownMenuItem
                  onClick={() => navigate(`/profiles/${authentication?.userDto.id}`)}
                  className="text-body3-m text-gray-100 cursor-pointer hover:bg-gray-700 focus:bg-gray-700"
                >
                  프로필
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={handleLogout}
                  className="text-body3-m text-red-notification cursor-pointer hover:bg-gray-700 focus:bg-gray-700"
                >
                  로그아웃
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </div>

      {/* Content Form Dialog */}
      <ContentFormDialog
        mode="create"
        open={isContentFormOpen}
        onOpenChange={setIsContentFormOpen}
      />
    </div>
  );
}
