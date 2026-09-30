import { useState, useEffect } from 'react';
import { useInView } from 'react-intersection-observer';
import {
  Dialog,
  DialogContent,
  DialogClose,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { MoreVertical } from 'lucide-react';
import { deleteReview } from '@/lib/api/reviews';
import useReviewStore from '@/lib/stores/useReviewStore';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import type { ReviewDto } from '@/lib/types';
import icX from '@/assets/ic_X.svg';
import icStarFull from '@/assets/ic_star_full.svg';
import ReviewWriteForm from './ReviewWriteForm';

interface ReviewListDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  contentId: string;
}

export default function ReviewListDialog({
  open,
  onOpenChange,
  contentId,
}: ReviewListDialogProps) {
  const [view, setView] = useState<'list' | 'write' | 'edit'>('list');
  const [editingReview, setEditingReview] = useState<ReviewDto | null>(null);
  const [deletingReviewId, setDeletingReviewId] = useState<string | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const { data, loading, fetch, fetchMore, hasNext, updateParams, clearData } = useReviewStore();
  const { ref: sentinelRef, inView } = useInView({
    threshold: 0,
    rootMargin: '100px',
  });

  // 모달이 열릴 때 리뷰 데이터 fetch
  useEffect(() => {
    if (open) {
      updateParams({ contentId, limit: 20 });
    } else {
      // 모달이 닫힐 때 view를 list로 리셋
      setView('list');
      clearData();
    }
  }, [open, contentId, updateParams, clearData]);

  // useInView로 무한 스크롤 구현
  useEffect(() => {
    if (open && view === 'list' && inView && hasNext() && !loading) {
      fetchMore();
    }
  }, [open, view, inView, hasNext, loading, fetchMore]);

  const handleWriteComplete = () => {
    // 리뷰 작성 완료 후 목록으로 돌아가기
    setView('list');
    setEditingReview(null);
    // 리뷰 목록 새로고침
    fetch();
  };

  const handleEdit = (review: ReviewDto) => {
    setEditingReview(review);
    setView('edit');
  };

  const handleDeleteClick = (reviewId: string) => {
    setDeletingReviewId(reviewId);
  };

  const handleDeleteConfirm = async () => {
    if (!deletingReviewId) return;

    setIsDeleting(true);
    try {
      // 1. API 호출
      await deleteReview(deletingReviewId);

      // 2. 스토어 동기화
      useReviewStore.getState().delete(deletingReviewId);

      // 3. 다이얼로그 닫기
      setDeletingReviewId(null);
    } catch (err) {
      console.error('Failed to delete review:', err);
      alert('리뷰 삭제에 실패했습니다.');
    } finally {
      setIsDeleting(false);
    }
  };

  const handleDeleteCancel = () => {
    setDeletingReviewId(null);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        hideCloseButton
        className="max-w-[1000px] h-[646px] bg-gray-800/50 backdrop-blur-[25px] border border-gray-800 rounded-3xl p-9 flex flex-col"
      >
        {view === 'list' ? (
          <>
            {/* 헤더 */}
            <div className="flex items-center justify-between pb-6 flex-shrink-0">
              <h2 className="text-title1-sb text-gray-300">리뷰</h2>
              <DialogClose asChild>
                <button className="w-6 h-6">
                  <img src={icX} alt="닫기" className="w-full h-full" />
                </button>
              </DialogClose>
            </div>

            {/* 리뷰 목록 - 스크롤 영역 */}
            <div className="flex-1 overflow-y-auto min-h-0">
              {data.length === 0 && !loading ? (
                <div className="flex items-center justify-center h-full">
                  <p className="text-body2-m text-gray-400">아직 리뷰가 없습니다.</p>
                </div>
              ) : (
                <>
                  {data.map((review) => (
                    <ReviewItem
                      key={review.id}
                      review={review}
                      onEdit={handleEdit}
                      onDelete={handleDeleteClick}
                    />
                  ))}
                  {/* Infinite Scroll Sentinel */}
                  {hasNext() && (
                    <div ref={sentinelRef} className="h-10 flex items-center justify-center">
                      {loading && (
                        <p className="text-body3-m text-gray-400">로딩 중...</p>
                      )}
                    </div>
                  )}
                </>
              )}
            </div>

            {/* 리뷰 작성 입력창 */}
            <button
              onClick={() => setView('write')}
              className="h-[54px] w-full bg-gray-800/50 border-[1.5px] border-gray-800 rounded-xl px-5 py-3.5 flex items-center mt-6 flex-shrink-0"
            >
              <span className="text-body2-m-140 text-gray-400">리뷰를 작성해주세요</span>
            </button>
          </>
        ) : view === 'write' ? (
          <ReviewWriteForm
            contentId={contentId}
            onCancel={() => setView('list')}
            onComplete={handleWriteComplete}
          />
        ) : (
          <ReviewWriteForm
            contentId={contentId}
            onCancel={() => {
              setView('list');
              setEditingReview(null);
            }}
            onComplete={handleWriteComplete}
            editMode
            initialData={editingReview || undefined}
          />
        )}

        {/* 삭제 확인 다이얼로그 */}
        {deletingReviewId && (
          <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60">
            <div className="bg-gray-800 rounded-2xl p-6 max-w-sm w-full mx-4">
              <h3 className="text-title1-sb text-gray-100 mb-2">리뷰 삭제</h3>
              <p className="text-body2-m text-gray-300 mb-6">
                정말 삭제하시겠습니까?
              </p>
              <div className="flex gap-3">
                <button
                  onClick={handleDeleteCancel}
                  disabled={isDeleting}
                  className="flex-1 h-[48px] bg-gray-700 rounded-xl text-body2-sb text-gray-100 hover:bg-gray-600 disabled:opacity-50"
                >
                  취소
                </button>
                <button
                  onClick={handleDeleteConfirm}
                  disabled={isDeleting}
                  className="flex-1 h-[48px] bg-pink-600 rounded-xl text-body2-sb text-white hover:bg-pink-700 disabled:opacity-50"
                >
                  {isDeleting ? '삭제 중...' : '삭제'}
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
  onEdit: (review: ReviewDto) => void;
  onDelete: (reviewId: string) => void;
}

function ReviewItem({ review, onEdit, onDelete }: ReviewItemProps) {
  const { data: jwt } = useAuthStore();
  const isOwner = jwt?.userDto.id === review.author.userId;

  // 사용자 프로필 배경색 생성 (간단한 해시 기반)
  const getProfileColor = (userId: string) => {
    const colors = ['#467db2', '#ac5959', '#7754a9', '#6e6e6e', '#5a9e6f', '#b87333'];
    const hash = userId.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0);
    return colors[hash % colors.length];
  };

  return (
    <div className="border-b border-[#212126] py-6 first:pt-6">
      {/* 사용자 정보 및 별점 */}
      <div className="flex items-center justify-between mb-3.5">
        <div className="flex items-center gap-2">
          {/* 프로필 */}
          <div className="flex items-center gap-1.5">
            <div
              className="w-[22px] h-[22px] rounded-full border border-white/10"
              style={{ backgroundColor: getProfileColor(review.author.userId) }}
            />
            <span className="text-body2-sb text-gray-300">{review.author.name}</span>
          </div>

          {/* 별점 */}
          <div className="flex items-center">
            {[1, 2, 3, 4, 5].map((star) => (
              <img
                key={star}
                src={icStarFull}
                alt="star"
                className="w-[18px] h-[18px]"
                style={{
                  opacity: star <= review.rating ? 1 : 0.3,
                }}
              />
            ))}
          </div>
        </div>

        {/* 미트볼 메뉴 (소유자만 표시) */}
        {isOwner && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button className="p-1 rounded hover:bg-gray-700 transition-colors">
                <MoreVertical className="w-5 h-5 text-gray-400" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent
              align="end"
              className="bg-gray-800 border-gray-700 min-w-[120px]"
            >
              <DropdownMenuItem
                onClick={() => onEdit(review)}
                className="text-body3-m text-gray-100 cursor-pointer hover:bg-gray-700 focus:bg-gray-700"
              >
                수정
              </DropdownMenuItem>
              <DropdownMenuItem
                onClick={() => onDelete(review.id)}
                className="text-body3-m text-red-notification cursor-pointer hover:bg-gray-700 focus:bg-gray-700"
              >
                삭제
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>

      {/* 리뷰 내용 */}
      <p className="text-body2-m-140 text-gray-50">{review.text}</p>
    </div>
  );
}
