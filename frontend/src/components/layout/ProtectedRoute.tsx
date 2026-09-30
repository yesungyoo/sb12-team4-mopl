import {Navigate, Outlet, useNavigate} from 'react-router-dom';
import { useEffect, useRef } from 'react';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import { LoadingSpinner } from '@/components/ui/loading-spinner';

/**
 * ProtectedRoute - 인증이 필요한 라우트를 보호
 * 인증되지 않은 사용자는 로그인 페이지로 리다이렉트
 *
 * 새로고침 시 refresh token을 사용하여 인증 상태 복구
 */
export default function ProtectedRoute() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated());
  const loading = useAuthStore((state) => state.loading);
  const fetch = useAuthStore((state) => state.fetch);
  const hasCheckedAuth = useRef(false);
  const navigate = useNavigate();

  useEffect(() => {
    // refresh token으로 인증 상태 복구 시도
    const restoreAuth = async () => {
      // 이미 인증된 상태라면 체크 불필요
      const currentAuth = useAuthStore.getState().isAuthenticated();
      if (currentAuth) {
        hasCheckedAuth.current = true;
        return;
      }

      try {
        await fetch({throwError: true});
      } catch (error) {
        // refresh token이 없거나 만료된 경우 - 로그인 페이지로 리다이렉트
        console.error('Failed to restore auth:', error);
        navigate('/sign-in');
      } finally {
        hasCheckedAuth.current = true;
      }
    };

    restoreAuth();
  }, [fetch]); // 마운트 시 한 번만 실행

  // 이미 인증된 상태라면 바로 통과
  if (isAuthenticated) {
    return <Outlet />;
  }

  // 인증 체크를 아직 시도하지 않았거나 로딩 중
  if (!hasCheckedAuth.current || loading) {
    return (
      <div className="flex h-screen w-full items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  // 인증되지 않은 경우 로그인 페이지로 리다이렉트
  if (!isAuthenticated) {
    return <Navigate to="/sign-in" replace />;
  }

  return <Outlet />;
}
