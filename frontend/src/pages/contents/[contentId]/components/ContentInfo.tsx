import { useState, useRef } from 'react';
import { Button } from '@/components/ui/button';
import icStarFull from '@/assets/ic_star_full.svg';
import type {ContentDto, ContentType} from '@/lib/types';
import ReviewListDialog from './ReviewListDialog';
import AddToPlaylistDialog from './AddToPlaylistDialog';

const ContentTypeLabel: Record<ContentType, string> = {
  movie: '영화',
  tvSeries: 'TV 시리즈',
  sport: '스포츠',
}

interface ContentInfoProps {
  content: ContentDto;
}

export default function ContentInfo({ content }: ContentInfoProps) {
  const [isReviewDialogOpen, setIsReviewDialogOpen] = useState(false);
  const [isPlaylistDialogOpen, setIsPlaylistDialogOpen] = useState(false);
  const tagScrollRef = useRef<HTMLDivElement>(null);
  const isDraggingRef = useRef(false);
  const startXRef = useRef(0);
  const scrollLeftRef = useRef(0);

  const handleMouseDown = (e: React.MouseEvent) => {
    if (!tagScrollRef.current) return;
    isDraggingRef.current = true;
    startXRef.current = e.pageX - tagScrollRef.current.offsetLeft;
    scrollLeftRef.current = tagScrollRef.current.scrollLeft;
    tagScrollRef.current.style.cursor = 'grabbing';
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!isDraggingRef.current || !tagScrollRef.current) return;
    e.preventDefault();
    const x = e.pageX - tagScrollRef.current.offsetLeft;
    const walk = (x - startXRef.current) * 1.5; // 스크롤 속도 조절
    tagScrollRef.current.scrollLeft = scrollLeftRef.current - walk;
  };

  const handleMouseUpOrLeave = () => {
    isDraggingRef.current = false;
    if (tagScrollRef.current) {
      tagScrollRef.current.style.cursor = 'grab';
    }
  };
  return (
    <div className="flex flex-col items-center gap-5">
      {/* 포스터 이미지 */}
      <div className="relative w-[376px] h-[564px] rounded-2xl overflow-hidden border border-gray-200">
        {content.thumbnailUrl ? (
          <img
            src={content.thumbnailUrl}
            alt={content.title}
            className="w-full h-full object-contain"
          />
        ) : (
          <div className="w-full h-full bg-gray-800 flex items-center justify-center">
            <span className="text-body2-m text-gray-500">No Image</span>
          </div>
        )}
        <div className="absolute inset-0 bg-black/20" />
      </div>

      {/* 제목 및 메타 정보 */}
      <div className="flex flex-col gap-[7px] w-[376px]">
        <h1 className="text-header1-sb text-gray-50">{content.title}</h1>

        <div className="flex items-center justify-between">
          {/* 왼쪽: 평점 및 장르 태그 */}
          <div className="flex flex-col gap-2">
            {/* 평점 - 클릭 시 리뷰 목록 모달 오픈 */}
            <button
              onClick={() => setIsReviewDialogOpen(true)}
              className="flex items-center gap-0.5 cursor-pointer hover:opacity-80 transition-opacity"
            >
              <img src={icStarFull} alt="star" className="w-3.5 h-3.5" />
              <span className="text-caption1-m text-gray-300">
                {content.averageRating?.toFixed(1) || '0.0'} ({content.reviewCount?.toLocaleString() || 0})
              </span>
            </button>

            {/* 장르 태그 */}
            <div
              ref={tagScrollRef}
              onMouseDown={handleMouseDown}
              onMouseMove={handleMouseMove}
              onMouseUp={handleMouseUpOrLeave}
              onMouseLeave={handleMouseUpOrLeave}
              className="flex items-center gap-[7px] overflow-x-auto scrollbar-hide mr-1.5 cursor-grab select-none"
            >
              <div className="px-2 py-1 bg-gray-800 rounded-full flex-shrink-0">
                <span className="text-caption1-sb text-gray-300">{ContentTypeLabel[content.type]}</span>
              </div>
              {content.tags && content.tags.length > 0 && content.tags.map((tag) => (
                <div key={tag} className="px-2 py-1 bg-gray-800 rounded-full flex-shrink-0">
                  <span className="text-caption1-sb text-gray-300">{tag}</span>
                </div>
              ))}
            </div>
          </div>

          {/* 오른쪽: 플레이리스트 추가 버튼 */}
          <Button
            onClick={() => setIsPlaylistDialogOpen(true)}
            className="h-[34px] px-3 py-2 bg-pink-600 hover:bg-pink-700 text-body3-b text-white rounded-[9px]"
          >
            플레이리스트에 추가
          </Button>
        </div>
      </div>

      {/* 설명 */}
      {content.description && (
        <p className="text-body3-m-150 text-gray-200 w-[376px]">
          {content.description}
        </p>
      )}

      {/* 리뷰 목록 모달 */}
      <ReviewListDialog
        open={isReviewDialogOpen}
        onOpenChange={setIsReviewDialogOpen}
        contentId={content.id}
      />

      {/* 플레이리스트 추가 모달 */}
      <AddToPlaylistDialog
        open={isPlaylistDialogOpen}
        onOpenChange={setIsPlaylistDialogOpen}
        contentId={content.id}
      />
    </div>
  );
}
