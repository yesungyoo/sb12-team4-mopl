import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';

import icStarFull from '@/assets/ic_star_full.svg';
import { Button } from '@/components/ui/button';
import type {
  ContentDto,
  ContentType,
} from '@/lib/types';

import AddToPlaylistDialog from './AddToPlaylistDialog';
import ReviewListDialog from './ReviewListDialog';

const ContentTypeLabel: Record<ContentType, string> = {
  movie: '영화',
  tvSeries: 'TV 시리즈',
  sport: '스포츠',
};

interface ContentInfoProps {
  content: ContentDto;
}

export default function ContentInfo({
  content,
}: ContentInfoProps) {
  const [
    isReviewDialogOpen,
    setIsReviewDialogOpen,
  ] = useState(false);

  const [
    isPlaylistDialogOpen,
    setIsPlaylistDialogOpen,
  ] = useState(false);

  const [showLeftTagFade, setShowLeftTagFade] =
    useState(false);

  const [showRightTagFade, setShowRightTagFade] =
    useState(false);

  const tagScrollRef =
    useRef<HTMLDivElement>(null);

  const isDraggingRef = useRef(false);
  const startXRef = useRef(0);
  const scrollLeftRef = useRef(0);

  const updateTagFade = useCallback(() => {
    const element = tagScrollRef.current;

    if (!element) {
      return;
    }

    const maxScrollLeft =
      element.scrollWidth - element.clientWidth;

    setShowLeftTagFade(
      element.scrollLeft > 2,
    );

    setShowRightTagFade(
      maxScrollLeft > 2
      && element.scrollLeft < maxScrollLeft - 2,
    );
  }, []);

  useEffect(() => {
    const element = tagScrollRef.current;

    if (!element) {
      return;
    }

    const frameId =
      requestAnimationFrame(updateTagFade);

    const resizeObserver =
      new ResizeObserver(updateTagFade);

    resizeObserver.observe(element);

    return () => {
      cancelAnimationFrame(frameId);
      resizeObserver.disconnect();
    };
  }, [
    content.tags,
    content.type,
    updateTagFade,
  ]);

  const handleMouseDown = (
    event: React.MouseEvent,
  ) => {
    const element = tagScrollRef.current;

    if (!element) {
      return;
    }

    isDraggingRef.current = true;
    startXRef.current =
      event.pageX - element.offsetLeft;

    scrollLeftRef.current =
      element.scrollLeft;

    element.style.cursor = 'grabbing';
  };

  const handleMouseMove = (
    event: React.MouseEvent,
  ) => {
    const element = tagScrollRef.current;

    if (
      !isDraggingRef.current
      || !element
    ) {
      return;
    }

    event.preventDefault();

    const x =
      event.pageX - element.offsetLeft;

    const walk =
      (x - startXRef.current) * 1.5;

    element.scrollLeft =
      scrollLeftRef.current - walk;

    updateTagFade();
  };

  const handleMouseUpOrLeave = () => {
    isDraggingRef.current = false;

    if (tagScrollRef.current) {
      tagScrollRef.current.style.cursor =
        'grab';
    }
  };

  return (
    <div
      className="
        flex
        flex-col
        items-center
        gap-5
      "
    >
      <div
        className="
          relative
          h-[564px]
          w-[376px]
          overflow-hidden
          rounded-2xl
          border
          border-gray-200
        "
      >
        {content.thumbnailUrl ? (
          <img
            src={content.thumbnailUrl}
            alt={content.title}
            className="
              h-full
              w-full
              object-contain
            "
          />
        ) : (
          <div
            className="
              flex
              h-full
              w-full
              items-center
              justify-center
              bg-gray-800
            "
          >
            <span
              className="
                text-body2-m
                text-gray-500
              "
            >
              No Image
            </span>
          </div>
        )}

        <div
          className="
            absolute
            inset-0
            bg-black/20
          "
        />
      </div>

      <div
        className="
          flex
          w-[376px]
          min-w-0
          flex-col
          gap-[7px]
          overflow-hidden
        "
      >
        <h1
          className="
            text-header1-sb
            text-gray-50
          "
        >
          {content.title}
        </h1>

        <button
          type="button"
          onClick={() =>
            setIsReviewDialogOpen(true)
          }
          className="
            flex
            w-fit
            cursor-pointer
            items-center
            gap-0.5
            transition-opacity
            hover:opacity-80
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
              text-gray-300
            "
          >
            {content.averageRating?.toFixed(1)
              || '0.0'}{' '}
            (
            {content.reviewCount?.toLocaleString()
              || 0}
            )
          </span>
        </button>

        <div
          className="
            relative
            w-full
            min-w-0
            overflow-hidden
          "
        >
          <div
            ref={tagScrollRef}
            onScroll={updateTagFade}
            onMouseDown={handleMouseDown}
            onMouseMove={handleMouseMove}
            onMouseUp={handleMouseUpOrLeave}
            onMouseLeave={handleMouseUpOrLeave}
            className="
              flex
              w-full
              min-w-0
              touch-pan-x
              select-none
              items-center
              gap-[7px]
              overflow-x-auto
              scrollbar-hide
              cursor-grab
              px-0.5
            "
          >
            <div
              className="
                flex-shrink-0
                rounded-full
                bg-gray-800
                px-2
                py-1
              "
            >
              <span
                className="
                  whitespace-nowrap
                  text-caption1-sb
                  text-gray-300
                "
              >
                {
                  ContentTypeLabel[
                    content.type
                  ]
                }
              </span>
            </div>

            {content.tags
              && content.tags.length > 0
              && content.tags.map((tag) => (
                <div
                  key={tag}
                  className="
                    flex-shrink-0
                    rounded-full
                    bg-gray-800
                    px-2
                    py-1
                  "
                >
                  <span
                    className="
                      whitespace-nowrap
                      text-caption1-sb
                      text-gray-300
                    "
                  >
                    {tag}
                  </span>
                </div>
              ))}
          </div>

          {showLeftTagFade && (
            <div
              className="
                pointer-events-none
                absolute
                inset-y-0
                left-0
                z-10
                w-10
                bg-gradient-to-r
                from-black
                via-black/80
                to-transparent
              "
            />
          )}

          {showRightTagFade && (
            <div
              className="
                pointer-events-none
                absolute
                inset-y-0
                right-0
                z-10
                w-10
                bg-gradient-to-l
                from-black
                via-black/80
                to-transparent
              "
            />
          )}
        </div>

        <Button
          onClick={() =>
            setIsPlaylistDialogOpen(true)
          }
          className="
            mt-1
            h-[34px]
            self-end
            rounded-[9px]
            bg-pink-600
            px-3
            py-2
            text-body3-b
            text-white
            hover:bg-pink-700
          "
        >
          플레이리스트에 추가
        </Button>
      </div>

      {content.description && (
        <p
          className="
            w-[376px]
            text-body3-m-150
            text-gray-200
          "
        >
          {content.description}
        </p>
      )}

      <ReviewListDialog
        open={isReviewDialogOpen}
        onOpenChange={
          setIsReviewDialogOpen
        }
        contentId={content.id}
      />

      <AddToPlaylistDialog
        open={isPlaylistDialogOpen}
        onOpenChange={
          setIsPlaylistDialogOpen
        }
        contentId={content.id}
      />
    </div>
  );
}
