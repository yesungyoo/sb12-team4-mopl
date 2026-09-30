import { Outlet } from 'react-router-dom';
import { Toaster } from '@/components/ui/sonner';
import bgNoise from '@/assets/bg_noise.svg';

/**
 * RootLayout - 인증 페이지용 기본 레이아웃
 * 현재는 최소 구조만 포함, 나중에 필요시 공통 요소 추가
 */
export default function RootLayout() {
  return (
    <div
      className="min-h-screen bg-background"
      style={{ backgroundImage: `url(${bgNoise})`, backgroundSize: 'cover', backgroundPosition: 'center' }}
    >
      <Outlet />
      <Toaster />
    </div>
  );
}
