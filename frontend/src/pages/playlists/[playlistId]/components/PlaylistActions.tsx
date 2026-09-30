import { Button } from '@/components/ui/button';
import type { PlaylistDto } from '@/lib/types';

interface PlaylistActionsProps {
  playlist: PlaylistDto;
  isOwner: boolean;
  isEditMode: boolean;
  onEdit: () => void;
  onSave: () => Promise<void>;
  onCancel: () => void;
  onDelete: () => void;
  onSubscribe: () => Promise<void>;
}

export default function PlaylistActions({
  playlist,
  isOwner,
  isEditMode,
  onEdit,
  onSave,
  onCancel,
  onDelete,
  onSubscribe,
}: PlaylistActionsProps) {
  if (!isOwner) {
    // Non-owner: Show subscribe button
    return (
      <Button
        onClick={onSubscribe}
        className={
          playlist.subscribedByMe
            ? 'bg-gray-700 hover:bg-gray-600 text-white text-body3-b px-3 py-2 h-[34px] rounded-lg'
            : 'bg-pink-600 hover:bg-pink-700 text-white text-body3-b px-3 py-2 h-[34px] rounded-lg'
        }
      >
        {playlist.subscribedByMe ? '구독 중' : '구독'}
      </Button>
    );
  }

  if (isEditMode) {
    // Owner in edit mode: Show cancel and save buttons
    return (
      <div className="flex gap-3">
        <Button
          onClick={onCancel}
          variant="outline"
          className="text-body3-b px-3 py-2 h-[34px] rounded-lg border-gray-600 text-gray-300 hover:bg-gray-800"
        >
          취소
        </Button>
        <Button
          onClick={onSave}
          className="bg-pink-600 hover:bg-pink-700 text-white text-body3-b px-3 py-2 h-[34px] rounded-lg"
        >
          저장
        </Button>
      </div>
    );
  }

  // Owner in view mode: Show edit and delete buttons
  return (
    <div className="flex gap-3">
      <Button
        onClick={onEdit}
        className="bg-gray-700 hover:bg-gray-600 text-white text-body3-b px-3 py-2 h-[34px] rounded-lg"
      >
        수정
      </Button>
      <Button
        onClick={onDelete}
        variant="destructive"
        className="text-body3-b px-3 py-2 h-[34px] rounded-lg"
      >
        삭제
      </Button>
    </div>
  );
}
