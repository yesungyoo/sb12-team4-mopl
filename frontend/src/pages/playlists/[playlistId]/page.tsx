import { useEffect, useState, type KeyboardEvent } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { getPlaylist, updatePlaylist, deletePlaylist, subscribePlaylist, unsubscribePlaylist, removeContentFromPlaylist } from '@/lib/api/playlists';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import type { PlaylistDto } from '@/lib/types';
import PlaylistActions from './components/PlaylistActions';
import PlaylistContentCard from './components/PlaylistContentCard';
import {LoadingSpinner} from '@/components/ui/loading-spinner';
import icStarFull from '@/assets/ic_star_full.svg';
import defaultProfileImg from '@/assets/ic_profile_default.svg';
import ConfirmDialog from '@/components/ui/confirm-dialog';
import { Button } from '@/components/ui/button';

export default function PlaylistDetailPage() {
  const { playlistId } = useParams<{ playlistId: string }>();
  const navigate = useNavigate();
  const { data: jwt } = useAuthStore();

  // Data state
  const [playlist, setPlaylist] = useState<PlaylistDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Edit mode state
  const [isEditMode, setIsEditMode] = useState(false);
  const [editedTitle, setEditedTitle] = useState('');
  const [editedDescription, setEditedDescription] = useState('');

  // Delete dialog state
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  // Fetch playlist data
  const fetchPlaylist = async () => {
    if (!playlistId) {
      setError('플레이리스트 ID가 없습니다.');
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError(null);
      const data = await getPlaylist(playlistId);
      setPlaylist(data);
    } catch {
      setError('플레이리스트를 불러오는데 실패했습니다.');
      toast.error('플레이리스트를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPlaylist();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playlistId]);

  // Check ownership
  const isOwner = playlist && jwt ? playlist.owner.userId === jwt.userDto.id : false;

  // Edit mode handlers
  const handleEdit = () => {
    if (!playlist) return;
    setEditedTitle(playlist.title);
    setEditedDescription(playlist.description);
    setIsEditMode(true);
  };

  const handleCancel = () => {
    setIsEditMode(false);
    setEditedTitle('');
    setEditedDescription('');
  };

  const handleSave = async () => {
    if (!playlistId || !playlist) return;

    if (!editedTitle.trim()) {
      toast.error('제목을 입력해주세요.');
      return;
    }

    if (!editedDescription.trim()) {
      toast.error('설명을 입력해주세요.');
      return;
    }

    try {
      await updatePlaylist(playlistId, {
        title: editedTitle,
        description: editedDescription,
      });
      toast.success('플레이리스트가 수정되었습니다.');
      setIsEditMode(false);
      await fetchPlaylist();
    } catch {
      toast.error('플레이리스트 수정에 실패했습니다.');
    }
  };

  const handleDelete = () => {
    setShowDeleteDialog(true);
  };

  const handleConfirmDelete = async () => {
    if (!playlistId) return;

    try {
      await deletePlaylist(playlistId);
      toast.success('플레이리스트가 삭제되었습니다.');
      navigate('/playlists');
    } catch {
      toast.error('플레이리스트 삭제에 실패했습니다.');
    }
    setShowDeleteDialog(false);
  };

  const handleSubscribe = async () => {
    if (!playlistId || !playlist) return;

    try {
      if (playlist.subscribedByMe) {
        await unsubscribePlaylist(playlistId);
        toast.success('구독이 취소되었습니다.');
      } else {
        await subscribePlaylist(playlistId);
        toast.success('구독되었습니다.');
      }
      await fetchPlaylist();
    } catch {
      toast.error('구독 처리에 실패했습니다.');
    }
  };

  const handleDeleteContent = async (contentId: string) => {
    if (!playlistId) return;

    try {
      await removeContentFromPlaylist(playlistId, contentId);
      toast.success('콘텐츠가 삭제되었습니다.');
      await fetchPlaylist();
    } catch {
      toast.error('콘텐츠 삭제에 실패했습니다.');
    }
  };

  // Korean IME support for Enter key
  const handleTitleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSave();
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <LoadingSpinner />
      </div>
    );
  }

  if (error || !playlist) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen gap-4">
        <p className="text-body1-m text-gray-300">{error || '플레이리스트를 찾을 수 없습니다.'}</p>
        <Button onClick={() => navigate('/playlists')}>목록으로 돌아가기</Button>
      </div>
    );
  }



  return (
    <div className="max-w-[1680px] mx-auto px-[70px] py-10">
      {/* Header Section */}
      <div className="flex flex-col gap-[30px] mb-[60px]">
        {/* Owner and Title Section */}
        <div className="flex flex-col gap-4">
          {/* Owner Profile */}
          <div className="flex items-center gap-1.5">
            <div className="w-5 h-5 rounded-full bg-gray-600 border border-white/10 overflow-hidden">
              <img src={playlist.owner.profileImageUrl || defaultProfileImg} alt="" className="w-full h-full object-cover" />
            </div>
            <span
                className="text-body2-m text-gray-300 cursor-pointer hover:text-gray-100 transition-colors"
                onClick={() => navigate(`/profiles/${playlist.owner.userId}`)}
            >{playlist.owner.name}</span>
          </div>

          {/* Title and Actions */}
          <div className="flex items-start justify-between">
            {/* Title */}
            {isEditMode ? (
              <input
                type="text"
                value={editedTitle}
                onChange={(e) => setEditedTitle(e.target.value)}
                onKeyDown={handleTitleKeyDown}
                className="text-header1-sb text-white bg-gray-800 border border-gray-600 rounded-lg px-4 py-2 flex-1 mr-4 focus:outline-none focus:ring-2 focus:ring-pink-600"
                placeholder="플레이리스트 제목"
              />
            ) : (
              <h1 className="text-header1-sb text-white flex-1">{playlist.title}</h1>
            )}

            {/* Subscriber Count and Actions */}
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1.5">
                <img src={icStarFull} alt="" className="w-4 h-4" />
                <span className="text-body1-m text-gray-200">
                  구독자 {playlist.subscriberCount.toLocaleString()}명
                </span>
              </div>
              <PlaylistActions
                playlist={playlist}
                isOwner={isOwner}
                isEditMode={isEditMode}
                onEdit={handleEdit}
                onSave={handleSave}
                onCancel={handleCancel}
                onDelete={handleDelete}
                onSubscribe={handleSubscribe}
              />
            </div>
          </div>
        </div>

        {/* Description Section */}
        <div className="backdrop-blur-[25px] bg-[rgba(46,46,56,0.4)] border border-gray-900 rounded-2xl px-[30px] py-6">
          {isEditMode ? (
            <textarea
              value={editedDescription}
              onChange={(e) => setEditedDescription(e.target.value)}
              className="text-body2-m-160 text-gray-100 bg-gray-800 border border-gray-600 rounded-lg px-4 py-3 w-full min-h-[130px] resize-none focus:outline-none focus:ring-2 focus:ring-pink-600"
              placeholder="플레이리스트 설명"
            />
          ) : (
            <p className="text-body2-m-160 text-gray-100 whitespace-pre-wrap">
              {playlist.description}
            </p>
          )}
        </div>
      </div>

      {/* Content List Section */}
      <div className="flex flex-col gap-[59px]">
        <h2 className="text-title1-b text-white">콘텐츠 목록</h2>

        {/* Content Grid */}
        {playlist.contents && playlist.contents.length > 0 ? (
          <div className="grid grid-cols-8 gap-x-5 gap-y-11">
            {playlist.contents.map((content) => (
              <PlaylistContentCard
                key={content.id}
                content={content}
                playlistId={playlistId!}
                canDelete={isOwner && !isEditMode}
                onDelete={() => handleDeleteContent(content.id)}
              />
            ))}
          </div>
        ) : (
          <p className="text-body1-m text-gray-400 text-center py-20">
            플레이리스트에 콘텐츠가 없습니다.
          </p>
        )}
      </div>

      {/* Delete Playlist Confirmation Dialog */}
      <ConfirmDialog
        open={showDeleteDialog}
        onOpenChange={setShowDeleteDialog}
        title="플레이리스트 삭제"
        description={`"${playlist.title}"을(를) 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.`}
        onConfirm={handleConfirmDelete}
        confirmText="삭제"
        cancelText="취소"
        variant="destructive"
      />
    </div>
  );
}
