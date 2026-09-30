import { Link } from 'react-router-dom';
import notFoundIcon from '@/assets/not_found.svg';

export default function NotFoundPage() {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="flex flex-col items-center gap-6 w-[333px]">
        {/* 404 Section */}
        <div className="flex flex-col items-center gap-3.5 w-full">
          {/* 4_4 with Icon */}
          <div className="flex flex-col items-center pb-1">
            <div className="flex items-center gap-0.5 -mb-1">
              <p className="text-[70px] font-bold leading-none tracking-[-1.4px] text-gray-200 whitespace-pre">
                4
              </p>
              <img src={notFoundIcon} alt="" className="w-14 h-14" />
              <p className="text-[70px] font-bold leading-none tracking-[-1.4px] text-gray-200 whitespace-pre">
                4
              </p>
            </div>
            <p className="text-[70px] font-bold leading-none tracking-[-1.4px] text-gray-200 -mb-1">
              Not Pound
            </p>
          </div>

          {/* Korean Subtitle */}
          <p className="text-header1-sb text-gray-400 w-full text-center">
            페이지를 찾을 수 없습니다.
          </p>
        </div>

        {/* CTA Button */}
        <Link
          to="/"
          className="flex items-center justify-center h-14 px-5 py-2.5 rounded-[200px] bg-gray-950/50 w-36"
        >
          <span className="text-body2-sb text-gray-200 whitespace-pre">홈으로 돌아가기</span>
        </Link>
      </div>
    </div>
  );
}
