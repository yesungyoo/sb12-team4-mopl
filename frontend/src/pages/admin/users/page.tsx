import { useCallback, useEffect, useState } from 'react';
import { useInView } from 'react-intersection-observer';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import useUserStore from '@/lib/stores/useUserStore';
import { updateUserLocked, updateUserRole } from '@/lib/api/users';
import type { UserRole, SortDirection } from '@/lib/types';
import SearchBar from './components/SearchBar';
import UserTable, { LoadingSkeleton } from './components/UserTable';
import ConfirmDialog from '@/components/ui/confirm-dialog';

type SortBy = 'name' | 'email' | 'createdAt' | 'isLocked' | 'role';

interface ConfirmDialogState {
  open: boolean;
  type: 'lock' | 'role';
  userId: string;
  currentValue: boolean | UserRole;
}

export default function AdminUsersPage() {
  const { ref, inView } = useInView();
  const { data, loading, error, fetch, updateParams, hasNext, fetchMore, params } = useUserStore();
  const [actionLoadingUserId, setActionLoadingUserId] = useState<string>();
  const [confirmDialog, setConfirmDialog] = useState<ConfirmDialogState>({
    open: false,
    type: 'lock',
    userId: '',
    currentValue: false,
  });

  // Initial fetch
  useEffect(() => {
    fetch();
  }, []);

  // Infinite scroll trigger
  useEffect(() => {
    if (inView && hasNext() && !loading) {
      fetchMore();
    }
  }, [inView, hasNext, loading, fetchMore]);

  // Search handler
  const handleSearch = useCallback((value: string) => {
    updateParams({ emailLike: value || undefined });
  }, [updateParams]);

  // Sort handler - cycles through: none → ASC → DESC → none
  const handleSort = useCallback(
    (sortBy: SortBy) => {
      const currentSortBy = params.sortBy;
      const currentDirection = params.sortDirection;

      if (currentSortBy !== sortBy) {
        // Different column clicked - start with ASC
        updateParams({ sortBy, sortDirection: 'ASCENDING' as SortDirection });
      } else if (currentDirection === 'ASCENDING') {
        // Same column, currently ASC - switch to DESC
        updateParams({ sortDirection: 'DESCENDING' as SortDirection });
      } else if (currentDirection === 'DESCENDING') {
        // Same column, currently DESC - clear sort
        updateParams({ sortBy: 'name', sortDirection: 'ASCENDING' });
      } else {
        // No sort active - start with ASC
        updateParams({ sortBy, sortDirection: 'ASCENDING' as SortDirection });
      }
    },
    [params.sortBy, params.sortDirection, updateParams]
  );

  // Lock toggle with confirmation
  const handleLockToggle = (userId: string, currentLocked: boolean) => {
    setConfirmDialog({
      open: true,
      type: 'lock',
      userId,
      currentValue: currentLocked,
    });
  };

  const confirmLockToggle = async () => {
    const { userId, currentValue } = confirmDialog;
    const newLocked = !(currentValue as boolean);
    setActionLoadingUserId(userId);
    try {
      await updateUserLocked(userId, { locked: newLocked });
      useUserStore.getState().update(userId, { locked: newLocked });
      toast.success(
        newLocked ? '계정이 잠금되었습니다.' : '계정 잠금이 해제되었습니다.'
      );
    } catch {
      toast.error('계정 잠금 상태 변경에 실패했습니다.');
    } finally {
      setActionLoadingUserId(undefined);
    }
  };

  // Role toggle with confirmation
  const handleRoleToggle = (userId: string, currentRole: UserRole) => {
    setConfirmDialog({
      open: true,
      type: 'role',
      userId,
      currentValue: currentRole,
    });
  };

  const confirmRoleToggle = async () => {
    const { userId, currentValue } = confirmDialog;
    const newRole = currentValue === 'ADMIN' ? 'USER' : 'ADMIN';
    setActionLoadingUserId(userId);
    try {
      await updateUserRole(userId, { role: newRole });
      useUserStore.getState().update(userId, { role: newRole });
      toast.success(`사용자 권한이 ${newRole}으로 변경되었습니다.`);
    } catch {
      toast.error('사용자 권한 변경에 실패했습니다.');
    } finally {
      setActionLoadingUserId(undefined);
    }
  };

  const getDialogContent = () => {
    if (confirmDialog.type === 'lock') {
      const locked = confirmDialog.currentValue as boolean;
      return {
        title: locked ? '계정 잠금 해제' : '계정 잠금',
        description: locked
          ? '이 사용자의 계정 잠금을 해제하시겠습니까?'
          : '이 사용자의 계정을 잠금하시겠습니까?',
        confirmText: locked ? '해제' : '잠금',
        variant: locked ? ('default' as const) : ('destructive' as const),
      };
    } else {
      const currentRole = confirmDialog.currentValue as UserRole;
      const newRole = currentRole === 'ADMIN' ? 'USER' : 'ADMIN';
      return {
        title: '권한 변경',
        description: `이 사용자의 권한을 ${newRole}으로 변경하시겠습니까?`,
        confirmText: '변경',
        variant: 'default' as const,
      };
    }
  };

  const dialogContent = getDialogContent();

  return (
    <div className="px-[70px] py-10">
      <h1 className="mb-6 text-header1-b text-white">사용자 관리</h1>

      <SearchBar onSearch={handleSearch} className="mb-6" />

      {error && (
        <div className="mb-4 rounded-lg bg-red-notification/10 p-4 text-body2-m text-red-notification">
          {error}
        </div>
      )}

      {loading && data.length === 0 ? (
        <LoadingSkeleton />
      ) : (
        <>
          <UserTable
            users={data}
            onLockToggle={handleLockToggle}
            onRoleToggle={handleRoleToggle}
            loadingUserId={actionLoadingUserId}
            sortBy={params.sortBy as SortBy | undefined}
            sortDirection={params.sortDirection}
            onSort={handleSort}
          />

          {/* Infinite scroll sentinel */}
          {hasNext() && (
            <div ref={ref} className="py-4 text-center">
              {loading && (
                <Loader2 className="mx-auto size-6 animate-spin text-gray-400" />
              )}
              </div>
          )}
        </>
      )}

      {/* Confirmation Dialog */}
      <ConfirmDialog
        open={confirmDialog.open}
        onOpenChange={(open) =>
          setConfirmDialog({ ...confirmDialog, open })
        }
        title={dialogContent.title}
        description={dialogContent.description}
        confirmText={dialogContent.confirmText}
        variant={dialogContent.variant}
        onConfirm={
          confirmDialog.type === 'lock' ? confirmLockToggle : confirmRoleToggle
        }
      />
    </div>
  );
}
