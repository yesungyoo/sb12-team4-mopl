import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import type {ContentDto, ContentType} from '@/lib/types';
import icStarFull from '@/assets/ic_star_full.svg';
import icMeatball from '@/assets/ic_meatball.svg';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import useContentStore from '@/lib/stores/useContentStore';
import { deleteContent } from '@/lib/api/contents';
import ContentFormDialog from './ContentFormDialog';
import ConfirmDialog from '@/components/ui/confirm-dialog';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu';

const ContentTypeLabel: Record<ContentType, string> = {
  movie: '영화',
  tvSeries: 'TV 시리즈',
  sport: '스포츠',
}

interface ContentCardProps {
  content: ContentDto;
}

export default function ContentCard({ content }: ContentCardProps) {
  const navigate = useNavigate();
  const { data: authentication } = useAuthStore();
  const [isEditDialogOpen, setIsEditDialogOpen] = useState(false);
  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);
  const [imageLoaded, setImageLoaded] = useState(false);
  const tagScrollRef = useRef<HTMLDivElement>(null);
  const isDraggingRef = useRef(false);
  const startXRef = useRef(0);
  const scrollLeftRef = useRef(0);

  const handleMouseDown = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!tagScrollRef.current) return;
    isDraggingRef.current = true;
    startXRef.current = e.pageX - tagScrollRef.current.offsetLeft;
    scrollLeftRef.current = tagScrollRef.current.scrollLeft;
    tagScrollRef.current.style.cursor = 'grabbing';
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    e.preventDefault();
    if (!isDraggingRef.current || !tagScrollRef.current) return;
    const x = e.pageX - tagScrollRef.current.offsetLeft;
    const walk = (x - startXRef.current) * 1.5;
    tagScrollRef.current.scrollLeft = scrollLeftRef.current - walk;
  };

  const handleMouseUpOrLeave = (e: React.MouseEvent) => {
    e.stopPropagation();
    isDraggingRef.current = false;
    if (tagScrollRef.current) {
      tagScrollRef.current.style.cursor = 'grab';
    }
  };

  const isAdmin = authentication?.userDto.role === 'ADMIN';

  const handleClick = () => {
    navigate(`/contents/${content.id}`);
  };

  const handleEdit = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsEditDialogOpen(true);
  };

  const handleDeleteClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsDeleteDialogOpen(true);
  };

  const handleDeleteConfirm = async () => {
    try {
      // Call API
      await deleteContent(content.id);

      // Sync store
      useContentStore.getState().delete(content.id);

      toast.success('콘텐츠가 삭제되었습니다.');
    } catch (error) {
      toast.error('콘텐츠 삭제에 실패했습니다.');
      console.error(error);
    }
  };

  return (
    <>
      <div
        onClick={handleClick}
        className="flex flex-col gap-3 cursor-pointer group transition-transform hover:scale-[1.02]"
      >
        {/* Thumbnail Container */}
        <div className="relative w-full aspect-[260/390] rounded-2xl overflow-hidden">
          {/* Skeleton Loader */}
          {!imageLoaded && (
            <div className="absolute inset-0 bg-gray-800 animate-pulse" />
          )}

          {/* Thumbnail Image */}
          <img
            src={content.thumbnailUrl || '/placeholder-movie.png'}
            alt={content.title}
            className="w-full h-full object-contain"
            onLoad={() => setImageLoaded(true)}
          />

          {/* Dark Overlay */}
          <div className="absolute inset-0 bg-black/20" />

          {/* Live Viewer Count Badge */}
          {content.watcherCount && content.watcherCount > 0 && (
            <div className="absolute top-3 left-3 flex items-center gap-1.5 px-2.5 py-2 rounded-full bg-black/70">
              <div className="w-2 h-2 rounded-full bg-[#ff0b0b]" />
              <span className="text-body3-sb text-white leading-none">
                {content.watcherCount.toLocaleString()}
              </span>
            </div>
          )}

          {/* Admin Menu Button */}
          {isAdmin && (
            <div className="absolute top-3 right-3 opacity-0 group-hover:opacity-100 transition-opacity">
              <DropdownMenu>
                <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
                  <button
                    className="flex items-center justify-center w-8 h-8 rounded-full bg-white/90 hover:bg-white border border-gray-300 shadow-lg transition-colors"
                    aria-label="콘텐츠 옵션"
                  >
                    <img src={icMeatball} alt="" className="w-5 h-5 text-gray-800" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent
                  align="end"
                  className="bg-gray-800 border-gray-700 min-w-[100px]"
                  onClick={(e) => e.stopPropagation()}
                >
                  <DropdownMenuItem
                    onClick={handleEdit}
                    className="text-body3-m text-gray-100 cursor-pointer hover:bg-gray-700 focus:bg-gray-700"
                  >
                    수정
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    onClick={handleDeleteClick}
                    className="text-body3-m text-red-notification cursor-pointer hover:bg-gray-700 focus:bg-gray-700"
                  >
                    삭제
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )}
        </div>

      {/* Content Info */}
      <div className="flex flex-col gap-1.5 pt-1.5">
        {/* Title */}
        <h3 className="text-body1-b text-gray-50 line-clamp-1">
          {content.title}
        </h3>

        {/* Description - Fixed height to maintain consistent card size */}
        <p className="text-body2-m text-gray-300 line-clamp-2 min-h-[2lh]">
          {content.description || '\u00A0'}
        </p>
      </div>

      {/* Rating */}
      <div className="flex items-center gap-0.5 pb-1">
        <img src={icStarFull} alt="" className="w-4 h-4" />
        <span className="text-body3-m text-gray-400">
            {content.averageRating.toFixed(1)} ({content.reviewCount || 0})
          </span>
      </div>

      {/* Category Tags */}
      {content.tags && content.tags.length > 0 && (
        <div
          ref={tagScrollRef}
          onClick={e => e.stopPropagation()}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUpOrLeave}
          onMouseLeave={handleMouseUpOrLeave}
          className="flex gap-1.5 items-center overflow-x-auto scrollbar-hide cursor-grab select-none"
        >
          <div
              key={'type'}
              className="px-2 py-1 rounded-full bg-gray-800 h-[26px] flex items-center justify-center flex-shrink-0"
          >
              <span className="text-body3-m text-gray-300 leading-none">
                {ContentTypeLabel[content.type]}
              </span>
          </div>
          {content.tags.map((tag) => (
            <div
              key={tag}
              className="px-2 py-1 rounded-full bg-gray-800 h-[26px] flex items-center justify-center flex-shrink-0"
            >
              <span className="text-body3-m text-gray-300 leading-none">
                {tag}
              </span>
            </div>
          ))}
        </div>
      )}
      </div>

      {/* Edit Dialog */}
      <ContentFormDialog
        mode="edit"
        open={isEditDialogOpen}
        onOpenChange={setIsEditDialogOpen}
        initialData={content}
      />

      {/* Delete Confirmation Dialog */}
      <ConfirmDialog
        open={isDeleteDialogOpen}
        onOpenChange={setIsDeleteDialogOpen}
        title="콘텐츠 삭제"
        description={`'${content.title}'을(를) 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.`}
        onConfirm={handleDeleteConfirm}
        confirmText="삭제"
        cancelText="취소"
        variant="destructive"
      />
    </>
  );
}
