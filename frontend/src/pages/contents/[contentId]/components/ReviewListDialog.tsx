import { useEffect, useState } from 'react';
import { useInView } from 'react-intersection-observer';
import { MoreVertical } from 'lucide-react';

import icStarFull from '@/assets/ic_star_full.svg';
import icX from '@/assets/ic_X.svg';
import {
  Dialog,
  DialogClose,
  DialogContent,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { deleteReview } from '@/lib/api/reviews';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import useContentDetailStore from '@/lib/stores/useContentDetailStore';
import useReviewStore from '@/lib/stores/useReviewStore';
import type { ReviewDto } from '@/lib/types';

import ReviewWriteForm from './ReviewWriteForm';

interface ReviewListDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  contentId: string;
}

type ReviewAuthorCompat =
  ReviewDto['author'] & {
    id?: string;
    userId?: string;
  };

function getReviewAuthorId(
  review: ReviewDto,
) {
  const author =
    review.author as ReviewAuthorCompat;

  return (
    author.userId
    ?? author.id
    ?? ''
  );
}

export default function ReviewListDialog({
  open,
  onOpenChange,
  contentId,
}: ReviewListDialogProps) {
  const [view, setView] =
    useState<
      'list' | 'write' | 'edit'
    >('list');

  const [
    editingReview,
    setEditingReview,
  ] = useState<ReviewDto | null>(null);

  const [
    deletingReviewId,
    setDeletingReviewId,
  ] = useState<string | null>(null);

  const [
    isDeleting,
    setIsDeleting,
  ] = useState(false);

  const {
    data,
    loading,
    error,
    fetch,
    fetchMore,
    hasNext,
    updateParams,
    clearData,
  } = useReviewStore();

  const {
    ref: sentinelRef,
    inView,
  } = useInView({
    threshold: 0,
    rootMargin: '100px',
  });

  useEffect(() => {
    if (open) {
      updateParams({
        contentId,
        limit: 20,
      });
    } else {
      setView('list');
      clearData();
    }
  }, [
    clearData,
    contentId,
    open,
    updateParams,
  ]);

  useEffect(() => {
    if (
      open
      && view === 'list'
      && inView
      && hasNext()
      && !loading
    ) {
      void fetchMore();
    }
  }, [
    fetchMore,
    hasNext,
    inView,
    loading,
    open,
    view,
  ]);

  const refreshContentDetail = () => {
    void useContentDetailStore
      .getState()
      .fetch({
        ignoreLoading: true,
      });
  };

  const handleRetry = () => {
    void fetch({
      ignoreLoading: true,
    });
  };

  const handleWriteComplete = () => {
    setView('list');
    setEditingReview(null);

    void fetch({
      ignoreLoading: true,
    });

    refreshContentDetail();
  };

  const handleEdit = (
    review: ReviewDto,
  ) => {
    setEditingReview(review);
    setView('edit');
  };

  const handleDeleteClick = (
    reviewId: string,
  ) => {
    setDeletingReviewId(reviewId);
  };

  const handleDeleteConfirm =
    async () => {
      if (!deletingReviewId) {
        return;
      }

      setIsDeleting(true);

      try {
        await deleteReview(
          deletingReviewId,
        );

        useReviewStore
          .getState()
          .delete(
            deletingReviewId,
          );

        setDeletingReviewId(null);

        refreshContentDetail();
      } catch (deleteError) {
        console.error(
          'Failed to delete review:',
          deleteError,
        );

        alert(
          '리뷰 삭제에 실패했습니다.',
        );
      } finally {
        setIsDeleting(false);
      }
    };

  const handleDeleteCancel = () => {
    setDeletingReviewId(null);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
    >
      <DialogContent
        hideCloseButton
        className="
          flex
          h-[646px]
          max-w-[1000px]
          flex-col
          rounded-3xl
          border
          border-gray-800
          bg-gray-800/50
          p-9
          backdrop-blur-[25px]
        "
      >
        {view === 'list' ? (
          <>
            <div className="flex flex-shrink-0 items-center justify-between pb-6">
              <h2 className="text-title1-sb text-gray-300">
                리뷰
              </h2>

              <DialogClose asChild>
                <button
                  type="button"
                  className="h-6 w-6"
                >
                  <img
                    src={icX}
                    alt="닫기"
                    className="h-full w-full"
                  />
                </button>
              </DialogClose>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto">
              {error
              && data.length === 0
              && !loading ? (
                <div
                  className="
                    flex
                    h-full
                    flex-col
                    items-center
                    justify-center
                    gap-4
                  "
                >
                  <p className="text-body2-m text-gray-400">
                    리뷰를 불러오지 못했습니다.
                  </p>

                  <button
                    type="button"
                    onClick={handleRetry}
                    className="
                      rounded-lg
                      bg-gray-700
                      px-4
                      py-2
                      text-body3-b
                      text-gray-100
                      transition
                      hover:bg-gray-600
                    "
                  >
                    다시 시도
                  </button>
                </div>
              ) : data.length === 0
                && !loading ? (
                  <div className="flex h-full items-center justify-center">
                    <p className="text-body2-m text-gray-400">
                      아직 리뷰가 없습니다.
                    </p>
                  </div>
                ) : (
                  <>
                    {data.map(
                      (review) => (
                        <ReviewItem
                          key={
                            review.id
                          }
                          review={
                            review
                          }
                          onEdit={
                            handleEdit
                          }
                          onDelete={
                            handleDeleteClick
                          }
                        />
                      ),
                    )}

                    {hasNext() && (
                      <div
                        ref={
                          sentinelRef
                        }
                        className="
                          flex
                          h-10
                          items-center
                          justify-center
                        "
                      >
                        {loading && (
                          <p className="text-body3-m text-gray-400">
                            로딩 중...
                          </p>
                        )}
                      </div>
                    )}
                  </>
                )}
            </div>

            <button
              type="button"
              onClick={() =>
                setView('write')
              }
              className="
                mt-6
                flex
                h-[54px]
                w-full
                flex-shrink-0
                items-center
                rounded-xl
                border-[1.5px]
                border-gray-800
                bg-gray-800/50
                px-5
                py-3.5
              "
            >
              <span className="text-body2-m-140 text-gray-400">
                리뷰를 작성해주세요
              </span>
            </button>
          </>
        ) : view === 'write' ? (
          <ReviewWriteForm
            contentId={contentId}
            onCancel={() =>
              setView('list')
            }
            onComplete={
              handleWriteComplete
            }
          />
        ) : (
          <ReviewWriteForm
            contentId={contentId}
            onCancel={() => {
              setView('list');
              setEditingReview(null);
            }}
            onComplete={
              handleWriteComplete
            }
            editMode
            initialData={
              editingReview
              || undefined
            }
          />
        )}

        {deletingReviewId && (
          <div
            className="
              fixed
              inset-0
              z-[100]
              flex
              items-center
              justify-center
              bg-black/60
            "
          >
            <div className="mx-4 w-full max-w-sm rounded-2xl bg-gray-800 p-6">
              <h3 className="mb-2 text-title1-sb text-gray-100">
                리뷰 삭제
              </h3>

              <p className="mb-6 text-body2-m text-gray-300">
                정말 삭제하시겠습니까?
              </p>

              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={
                    handleDeleteCancel
                  }
                  disabled={
                    isDeleting
                  }
                  className="
                    h-[48px]
                    flex-1
                    rounded-xl
                    bg-gray-700
                    text-body2-sb
                    text-gray-100
                    hover:bg-gray-600
                    disabled:opacity-50
                  "
                >
                  취소
                </button>

                <button
                  type="button"
                  onClick={
                    handleDeleteConfirm
                  }
                  disabled={
                    isDeleting
                  }
                  className="
                    h-[48px]
                    flex-1
                    rounded-xl
                    bg-pink-600
                    text-body2-sb
                    text-white
                    hover:bg-pink-700
                    disabled:opacity-50
                  "
                >
                  {isDeleting
                    ? '삭제 중...'
                    : '삭제'}
                </button>
              </div>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface ReviewItemProps {
  review: ReviewDto;
  onEdit: (
    review: ReviewDto,
  ) => void;
  onDelete: (
    reviewId: string,
  ) => void;
}

function ReviewItem({
  review,
  onEdit,
  onDelete,
}: ReviewItemProps) {
  const { data: jwt } =
    useAuthStore();

  const authorId =
    getReviewAuthorId(review);

  const isOwner =
    authorId.length > 0
    && jwt?.userDto.id
      === authorId;

  const getProfileColor = (
    seed: string,
  ) => {
    const colors = [
      '#467db2',
      '#ac5959',
      '#7754a9',
      '#6e6e6e',
      '#5a9e6f',
      '#b87333',
    ];

    const hash = seed
      .split('')
      .reduce(
        (acc, char) =>
          acc
          + char.charCodeAt(0),
        0,
      );

    return colors[
      hash % colors.length
    ];
  };

  const profileColorSeed =
    authorId
    || review.author.name
    || review.id;

  return (
    <div className="border-b border-[#212126] py-6 first:pt-6">
      <div className="mb-3.5 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5">
            <div
              className="
                h-[22px]
                w-[22px]
                rounded-full
                border
                border-white/10
              "
              style={{
                backgroundColor:
                  getProfileColor(
                    profileColorSeed,
                  ),
              }}
            />

            <span className="text-body2-sb text-gray-300">
              {review.author.name}
            </span>
          </div>

          <div className="flex items-center">
            {[1, 2, 3, 4, 5].map(
              (star) => (
                <img
                  key={star}
                  src={icStarFull}
                  alt="star"
                  className="
                    h-[18px]
                    w-[18px]
                  "
                  style={{
                    opacity:
                      star
                      <= review.rating
                        ? 1
                        : 0.3,
                  }}
                />
              ),
            )}
          </div>
        </div>

        {isOwner && (
          <DropdownMenu>
            <DropdownMenuTrigger
              asChild
            >
              <button
                type="button"
                className="
                  rounded
                  p-1
                  transition-colors
                  hover:bg-gray-700
                "
              >
                <MoreVertical className="h-5 w-5 text-gray-400" />
              </button>
            </DropdownMenuTrigger>

            <DropdownMenuContent
              align="end"
              className="
                min-w-[120px]
                border-gray-700
                bg-gray-800
              "
            >
              <DropdownMenuItem
                onClick={() =>
                  onEdit(review)
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
                onClick={() =>
                  onDelete(
                    review.id,
                  )
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
        )}
      </div>

      <p className="text-body2-m-140 text-gray-50">
        {review.text}
      </p>
    </div>
  );
}
