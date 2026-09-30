import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getConversationWithUser, createConversation } from '@/lib/api/conversations';
import type { ConversationDto } from '@/lib/types';
import {isAxiosError} from "axios";
import {toast} from "sonner";

export default function ConversationWithPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const userId = searchParams.get('userId');

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const abortController = new AbortController();

    const initializeConversation = async () => {
      // Validate userId exists
      if (!userId) {
        setError('사용자 ID가 필요합니다.');
        setLoading(false);
        return;
      }

      try {
        setLoading(true);
        setError(null);

        let existingConversation: ConversationDto | null = null;

        // Check if conversation exists (handle 404 as "not found")
        try {
          existingConversation = await getConversationWithUser(userId);
        } catch (err) {
          // 404 means no conversation exists yet, which is fine
          if (isAxiosError(err) && err.response?.status === 404) {
            // Continue to create new conversation
          } else {
            throw err; // Re-throw if it's not a 404
          }
        }

        // Check if request was aborted before navigation
        if (abortController.signal.aborted) return;

        if (existingConversation) {
          // Redirect to existing conversation
          navigate(`/conversations/${existingConversation.id}`, { replace: true });
        } else {
          // Create new conversation
          const newConversation: ConversationDto = await createConversation({
            withUserId: userId,
          });
          toast.info('새로운 대화를 시작합니다.');

          // Check if request was aborted before navigation
          if (abortController.signal.aborted) return;

          // Redirect to new conversation
          navigate(`/conversations/${newConversation.id}`, { replace: true });
        }
      } catch (err) {
        // Ignore abort errors
        if (err instanceof Error && err.name === 'AbortError') return;

        console.error('Failed to initialize conversation:', err);
        setError('대화를 시작할 수 없습니다. 다시 시도해 주세요.');
      } finally {
        if (!abortController.signal.aborted) {
          setLoading(false);
        }
      }
    };

    initializeConversation();

    // Cleanup function: abort ongoing request on unmount or re-run
    return () => {
      abortController.abort();
    };
  }, [userId, navigate]);

  if (error) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <p className="text-body2-m text-red-notification">{error}</p>
          <button
            onClick={() => navigate('/conversations', { replace: true })}
            className="mt-4 rounded-lg bg-pink-500 px-4 py-2 text-body3-sb text-white hover:bg-pink-600"
          >
            대화 목록으로 돌아가기
          </button>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <div className="mb-4 h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-pink-500 mx-auto"></div>
          <p className="text-body2-m text-gray-500">대화를 준비하는 중...</p>
        </div>
      </div>
    );
  }

  return null;
}
