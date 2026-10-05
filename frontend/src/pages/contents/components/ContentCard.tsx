import { ImageOff } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import icMeatball from '@/assets/ic_meatball.svg';
import icStarFull from '@/assets/ic_star_full.svg';
import ConfirmDialog from '@/components/ui/confirm-dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { deleteContent } from '@/lib/api/contents';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import useContentStore from '@/lib/stores/useContentStore';
import type {
  ContentDto,
  ContentType,
} from '@/lib/types';

import ContentFormDialog from './ContentFormDialog';

type RuntimeContentType =
  | ContentType
  | 'MOVIE'
  | 'TV_SERIES'
  | 'SPORT';

const CONTENT_TYPE_LABEL: Record<
  RuntimeContentType,
  string
> = {
  movie: '영화',
  tvSeries: 'TV 시리즈',
  sport: '스포츠',
  MOVIE: '영화',
  TV_SERIES: 'TV 시리즈',
  SPORT: '스포츠',
};

interface ContentCardProps {
  content: ContentDto;
}

function getContentTypeLabel(
  type: ContentDto['type'],
) {
  return CONTENT_TYPE_LABEL[
    type as RuntimeContentType
  ];
}

export default function ContentCard({
  content,
}: ContentCardProps) {
  const navigate = useNavigate();
  const { data: authentication } =
    useAuthStore();

  const [
    isEditDialogOpen,
    setIsEditDialogOpen,
  ] = useState(false);

  const [
    isDeleteDialogOpen,
    setIsDeleteDialogOpen,
  ] = useState(false);

  const [imageLoaded, setImageLoaded] =
    useState(false);

  const [imageError, setImageError] =
    useState(false);

  const hasThumbnail =
    Boolean(content.thumbnailUrl)
    && !imageError;

  const isAdmin =
    authentication?.userDto.role
    === 'ADMIN';

  const typeLabel =
    getContentTypeLabel(content.type);

  const handleClick = () => {
    navigate(
      `/contents/${content.id}`,
    );
  };

  const handleEdit = (
    event: React.MouseEvent,
  ) => {
    event.stopPropagation();
    setIsEditDialogOpen(true);
  };

  const handleDeleteClick = (
    event: React.MouseEvent,
  ) => {
    event.stopPropagation();
    setIsDeleteDialogOpen(true);
  };

  const handleDeleteConfirm =
    async () => {
      try {
        await deleteContent(
          content.id,
        );

        useContentStore
          .getState()
          .delete(content.id);

        toast.success(
          '콘텐츠가 삭제되었습니다.',
        );
      } catch (error) {
        toast.error(
          '콘텐츠 삭제에 실패했습니다.',
        );

        console.error(error);
      }
    };

  return (
    <>
      <div
        onClick={handleClick}
        className="
          group
          flex
          min-w-0
          cursor-pointer
          flex-col
          text-left
        "
      >
        <div
          className="
            relative
            aspect-[260/390]
            w-full
            overflow-hidden
            rounded-2xl
            bg-gray-800
          "
        >
          {hasThumbnail ? (
            <>
              {!imageLoaded && (
                <div
                  className="
                    absolute
                    inset-0
                    animate-pulse
                    bg-gray-800
                  "
                />
              )}

              <img
                src={
                  content.thumbnailUrl
                  ?? undefined
                }
                alt=""
                className={[
                  'h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]',
                  imageLoaded
                    ? 'opacity-100'
                    : 'opacity-0',
                ].join(' ')}
                onLoad={() =>
                  setImageLoaded(true)
                }
                onError={() => {
                  setImageError(true);
                  setImageLoaded(true);
                }}
              />

              <div
                className="
                  pointer-events-none
                  absolute
                  inset-0
                  bg-gradient-to-t
                  from-black/50
                  via-transparent
                  to-transparent
                "
              />
            </>
          ) : (
            <div
              className="
                flex
                h-full
                w-full
                flex-col
                items-center
                justify-center
                gap-2
                bg-gray-800
                text-gray-500
              "
            >
              <ImageOff className="h-8 w-8" />

              <span className="text-caption1-m">
                썸네일 없음
              </span>
            </div>
          )}

          {content.watcherCount > 0 && (
            <div
              className="
                absolute
                left-3
                top-3
                flex
                items-center
                gap-1
                rounded-full
                bg-black/70
                px-2
                py-1.5
              "
            >
              <div
                className="
                  h-1.5
                  w-1.5
                  rounded-full
                  bg-[#ff0b0b]
                "
              />

              <span
                className="
                  text-caption1-sb
                  leading-none
                  text-white
                "
              >
                {content.watcherCount
                  .toLocaleString()}
              </span>
            </div>
          )}

          {typeLabel && (
            <span
              className="
                absolute
                bottom-3
                left-3
                inline-flex
                h-[22px]
                items-center
                rounded-full
                bg-black/70
                px-2
                text-[11px]
                font-semibold
                leading-none
                text-gray-100
              "
            >
              {typeLabel}
            </span>
          )}

          {isAdmin && (
            <div
              className="
                absolute
                right-3
                top-3
                opacity-0
                transition-opacity
                group-hover:opacity-100
              "
            >
              <DropdownMenu>
                <DropdownMenuTrigger
                  asChild
                  onClick={(event) =>
                    event.stopPropagation()
                  }
                >
                  <button
                    type="button"
                    aria-label="콘텐츠 옵션"
                    className="
                      flex
                      h-8
                      w-8
                      items-center
                      justify-center
                      rounded-full
                      border
                      border-gray-300
                      bg-white/90
                      shadow-lg
                      transition-colors
                      hover:bg-white
                    "
                  >
                    <img
                      src={icMeatball}
                      alt=""
                      className="h-5 w-5"
                    />
                  </button>
                </DropdownMenuTrigger>

                <DropdownMenuContent
                  align="end"
                  onClick={(event) =>
                    event.stopPropagation()
                  }
                  className="
                    min-w-[100px]
                    border-gray-700
                    bg-gray-800
                  "
                >
                  <DropdownMenuItem
                    onClick={
                      handleEdit
                    }
                    className="
                      cursor-pointer
                      text-body3-m
                      text-gray-100
                      hover:bg-gray-700
                      focus:bg-gray-700
                    "
                  >
                    수정
                  </DropdownMenuItem>

                  <DropdownMenuItem
                    onClick={
                      handleDeleteClick
                    }
                    className="
                      cursor-pointer
                      text-body3-m
                      text-red-notification
                      hover:bg-gray-700
                      focus:bg-gray-700
                    "
                  >
                    삭제
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )}
        </div>

        <div
          className="
            mt-3
            flex
            min-h-[84px]
            min-w-0
            flex-col
          "
        >
          <h3
            title={content.title}
            className="
              line-clamp-1
              min-w-0
              text-body1-b
              text-gray-50
            "
          >
            {content.title}
          </h3>

          <div
            className="
              mt-1
              flex
              min-h-[18px]
              items-center
              gap-1
            "
          >
            <img
              src={icStarFull}
              alt=""
              className="h-3.5 w-3.5"
            />

            <span
              className="
                text-caption1-m
                text-gray-400
              "
            >
              {content.averageRating
                .toFixed(1)}
            </span>
          </div>

          {content.tags
            && content.tags.length > 0
            && (
              <div
                className="
                  mt-auto
                  flex
                  h-[22px]
                  min-w-0
                  gap-1
                  overflow-hidden
                "
              >
                {content.tags
                  .slice(0, 2)
                  .map(
                    (tag, index) => (
                      <span
                        key={`${tag}-${index}`}
                        title={tag}
                        className="
                          inline-flex
                          h-[22px]
                          max-w-[49%]
                          min-w-0
                          flex-shrink
                          items-center
                          overflow-hidden
                          rounded-full
                          bg-gray-900
                          px-1.5
                          text-[11px]
                          font-medium
                          text-gray-400
                        "
                      >
                        <span
                          className="
                            block
                            min-w-0
                            truncate
                            leading-none
                          "
                        >
                          #{tag}
                        </span>
                      </span>
                    ),
                  )}
              </div>
            )}
        </div>
      </div>

      <ContentFormDialog
        mode="edit"
        open={isEditDialogOpen}
        onOpenChange={
          setIsEditDialogOpen
        }
        initialData={content}
      />

      <ConfirmDialog
        open={isDeleteDialogOpen}
        onOpenChange={
          setIsDeleteDialogOpen
        }
        title="콘텐츠 삭제"
        description={`'${content.title}'을(를) 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.`}
        onConfirm={
          handleDeleteConfirm
        }
        confirmText="삭제"
        cancelText="취소"
        variant="destructive"
      />
    </>
  );
}
