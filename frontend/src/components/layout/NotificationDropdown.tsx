import { useEffect, useRef } from 'react';
import icInfo from '@/assets/ic_info.svg';
import icWarning from '@/assets/ic_warning.svg';
import icError from '@/assets/ic_error.svg';
import useNotificationStore from '@/lib/stores/useNotificationStore';
import { formatRelativeTime } from '@/lib/utils/time';
import { markNotificationAsRead } from '@/lib/api/notifications';
import type { NotificationDto, NotificationLevel } from '@/lib/types';

interface NotificationDropdownProps {
  onClose: () => void;
}

function getNotificationIcon(level: NotificationLevel): string {
  switch (level) {
    case 'INFO':
      return icInfo;
    case 'WARNING':
      return icWarning;
    case 'ERROR':
      return icError;
  }
}

function getNotificationIconBgColor(level: NotificationLevel): string {
  switch (level) {
    case 'INFO':
      return 'bg-gray-800';
    case 'WARNING':
      return 'bg-yello-900/30';
    case 'ERROR':
      return 'bg-red-notification/20';
  }
}

interface NotificationItemProps {
  notification: NotificationDto;
  onRead: (id: string) => void;
}

function NotificationItem({ notification, onRead }: NotificationItemProps) {
  const handleClick = async () => {
    try {
      await markNotificationAsRead(notification.id);
      onRead(notification.id);
    } catch (error) {
      console.error('Failed to mark notification as read:', error);
    }
  };

  return (
    <div
      className="flex flex-col gap-3.5 border-b border-gray-800 p-6 cursor-pointer hover:bg-gray-900/30 transition-colors"
      onClick={handleClick}
    >
      <div className="flex items-start justify-between gap-1 w-full">
        <div className="flex items-center gap-3 flex-1 min-w-0">
          {/* Icon */}
          <div className={`flex items-center justify-center rounded-[14px] p-1.5 shrink-0 size-11 ${getNotificationIconBgColor(notification.level)}`}>
            <img
              src={getNotificationIcon(notification.level)}
              alt=""
              className="size-[26px]"
            />
          </div>

          {/* Content */}
          <div className="flex flex-col gap-1.5 flex-1 min-w-0">
            <div className="flex items-start gap-0.5">
              <p className="text-body1-m text-gray-200 truncate">{notification.title}</p>
            </div>
            <p className="text-body3-m text-gray-500">
              {formatRelativeTime(notification.createdAt)}
            </p>
          </div>
        </div>

        {/* Unread indicator - always show red dot for all notifications in the list */}
        <div className="bg-red-notification rounded-full size-2 shrink-0 mt-2" />
      </div>

      {/* Additional content message if exists */}
      {notification.content && (
        <div className="bg-gray-800/50 rounded-2xl px-5 py-3.5">
          <p className="text-body2-m-140 text-gray-300 line-clamp-2">
            {notification.content}
          </p>
        </div>
      )}
    </div>
  );
}

export default function NotificationDropdown({ onClose }: NotificationDropdownProps) {
  const { data, loading, error, fetch, fetchMore, hasNext, count } = useNotificationStore();
  const dropdownRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Fetch notifications on mount
  useEffect(() => {
    fetch();
  }, [fetch]);

  // Close dropdown when clicking outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        onClose();
      }
    }

    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [onClose]);

  // Handle infinite scroll
  useEffect(() => {
    const scrollElement = scrollRef.current;
    if (!scrollElement) return;

    function handleScroll() {
      if (!scrollElement) return;
      const { scrollTop, scrollHeight, clientHeight } = scrollElement;
      const scrolledToBottom = scrollHeight - scrollTop - clientHeight < 100;

      if (scrolledToBottom && hasNext() && !loading) {
        fetchMore();
      }
    }

    scrollElement.addEventListener('scroll', handleScroll);
    return () => {
      scrollElement.removeEventListener('scroll', handleScroll);
    };
  }, [hasNext, loading, fetchMore]);

  const handleNotificationRead = (id: string) => {
    useNotificationStore.getState().delete(id);
  };

  return (
    <div
      ref={dropdownRef}
      className="absolute top-16 right-0 w-[480px] max-h-[812px] bg-gray-900/90 backdrop-blur-[25px] border border-gray-800 rounded-3xl flex flex-col overflow-hidden shadow-xl z-50"
    >
      {/* Header */}
      <div className="flex items-center gap-1 px-6 pt-[30px] pb-3.5 shrink-0">
        <h2 className="text-title1-sb text-gray-200">알림</h2>
        <span className="text-body1-sb text-gray-400">{count()}</span>
      </div>

      {/* Notification List */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        {error && (
          <div className="flex items-center justify-center p-6">
            <p className="text-body2-m text-red-notification">{error}</p>
          </div>
        )}

        {!error && data.length === 0 && !loading && (
          <div className="flex items-center justify-center p-12">
            <p className="text-body2-m text-gray-500">알림이 없습니다</p>
          </div>
        )}

        {!error && data.length > 0 && (
          <>
            {data.map((notification) => (
              <NotificationItem
                key={notification.id}
                notification={notification}
                onRead={handleNotificationRead}
              />
            ))}
          </>
        )}

        {loading && (
          <div className="flex items-center justify-center p-6">
            <p className="text-body2-m text-gray-500">로딩 중...</p>
          </div>
        )}
      </div>
    </div>
  );
}
