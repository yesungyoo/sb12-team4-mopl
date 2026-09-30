import { useState, useEffect } from 'react';
import {
  Dialog,
  DialogContent,
  DialogClose,
} from '@/components/ui/dialog';
import { toast } from 'sonner';
import { getPlaylists, createPlaylist, addContentToPlaylist } from '@/lib/api/playlists';
import usePlaylistStore from '@/lib/stores/usePlaylistStore';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import type { PlaylistDto, PlaylistCreateRequest } from '@/lib/types';
import icX from '@/assets/ic_X.svg';
import icArrowLeft from '@/assets/ic_arrow_left.svg';

interface AddToPlaylistDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  contentId: string;
}

export default function AddToPlaylistDialog({
  open,
  onOpenChange,
  contentId,
}: AddToPlaylistDialogProps) {
  const [view, setView] = useState<'list' | 'create'>('list');
  const [userPlaylists, setUserPlaylists] = useState<PlaylistDto[]>([]);
  const [selectedPlaylistIds, setSelectedPlaylistIds] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const { data: jwt } = useAuthStore();

  // 모달이 열릴 때 사용자의 플레이리스트 fetch
  useEffect(() => {
    if (open && jwt?.userDto.id) {
      fetchUserPlaylists();
    } else {
      // 모달이 닫힐 때 상태 초기화
      setView('list');
      setUserPlaylists([]);
      setSelectedPlaylistIds(new Set());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, jwt?.userDto.id]);

  const fetchUserPlaylists = async () => {
    if (!jwt?.userDto.id) return;

    setLoading(true);
    try {
      const response = await getPlaylists({
        ownerIdEqual: jwt.userDto.id,
        limit: 100,
        sortDirection: 'DESCENDING',
        sortBy: 'updatedAt',
      });

      // 이미 추가된 콘텐츠가 있는 플레이리스트 필터링
      const notAddedPlaylists = response.data.filter((playlist) => {
        const hasContent = playlist.contents.some((content) => content.id === contentId);
        return !hasContent; // 콘텐츠가 없는 플레이리스트만 표시
      });

      setUserPlaylists(notAddedPlaylists);
      setSelectedPlaylistIds(new Set()); // 초기 선택 없음
    } catch (err) {
      console.error('Failed to fetch playlists:', err);
      toast.error('플레이리스트를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleCheckboxChange = (playlistId: string, isChecked: boolean) => {
    setSelectedPlaylistIds((prev) => {
      const newSet = new Set(prev);
      if (isChecked) {
        newSet.add(playlistId);
      } else {
        newSet.delete(playlistId);
      }
      return newSet;
    });
  };

  const handleAddToPlaylists = async () => {
    setAdding(true);
    try {
      // 선택된 플레이리스트에 콘텐츠 추가
      await Promise.all(
        Array.from(selectedPlaylistIds).map((playlistId) =>
          addContentToPlaylist(playlistId, contentId)
        )
      );

      toast.success('플레이리스트에 추가되었습니다.');
      onOpenChange(false);
    } catch (err) {
      console.error('Failed to add content to playlists:', err);
      toast.error('플레이리스트 추가에 실패했습니다.');
    } finally {
      setAdding(false);
    }
  };

  const handleCreatePlaylist = async (data: PlaylistCreateRequest) => {
    try {
      // 1. API 호출
      const newPlaylist = await createPlaylist(data);

      // 2. 스토어 동기화
      usePlaylistStore.getState().add(newPlaylist);

      // 3. 새로 생성된 플레이리스트에 콘텐츠 추가
      await addContentToPlaylist(newPlaylist.id, contentId);

      toast.success('플레이리스트가 생성되고 콘텐츠가 추가되었습니다.');
      onOpenChange(false);
    } catch (err) {
      console.error('Failed to create playlist:', err);
      toast.error('플레이리스트 생성에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        hideCloseButton
        className="max-w-[500px] max-h-[min(646px,calc(100vh-2rem))] overflow-hidden bg-gray-800/50 backdrop-blur-[25px] border border-gray-800 rounded-3xl p-9"
      >
        {view === 'list' ? (
          <PlaylistListView
            userPlaylists={userPlaylists}
            selectedPlaylistIds={selectedPlaylistIds}
            loading={loading}
            adding={adding}
            onCheckboxChange={handleCheckboxChange}
            onAddToPlaylists={handleAddToPlaylists}
            onCreateNew={() => setView('create')}
            onClose={() => onOpenChange(false)}
          />
        ) : (
          <CreatePlaylistView
            onBack={() => setView('list')}
            onCreate={handleCreatePlaylist}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

interface PlaylistListViewProps {
  userPlaylists: PlaylistDto[];
  selectedPlaylistIds: Set<string>;
  loading: boolean;
  adding: boolean;
  onCheckboxChange: (playlistId: string, isChecked: boolean) => void;
  onAddToPlaylists: () => void;
  onCreateNew: () => void;
  onClose: () => void;
}

function PlaylistListView({
  userPlaylists,
  selectedPlaylistIds,
  loading,
  adding,
  onCheckboxChange,
  onAddToPlaylists,
  onCreateNew,
  onClose,
}: PlaylistListViewProps) {
  return (
    <div className="flex flex-col h-full min-h-0">
      {/* 헤더 */}
      <div className="flex items-center justify-between pb-6 shrink-0">
        <h2 className="text-title1-sb text-gray-300">플레이리스트 추가</h2>
        <DialogClose asChild>
          <button className="w-6 h-6" onClick={onClose}>
            <img src={icX} alt="닫기" className="w-full h-full" />
          </button>
        </DialogClose>
      </div>

      {/* 플레이리스트 목록: 헤더(48px)+버튼(54px)+여백(92px)을 제외한 나머지 뷰포트 높이만큼만 스크롤 영역 확보 */}
      <div className="max-h-[min(452px,calc(100vh-226px))] overflow-y-auto mb-5">
        {loading ? (
          <div className="flex items-center justify-center h-full">
            <p className="text-body2-m text-gray-400">로딩 중...</p>
          </div>
        ) : userPlaylists.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <p className="text-body2-m text-gray-400">콘텐츠를 추가할 플레이리스트가 없습니다.</p>
          </div>
        ) : (
          <div className="space-y-3">
            {userPlaylists.map((playlist) => (
              <PlaylistCheckboxItem
                key={playlist.id}
                playlist={playlist}
                checked={selectedPlaylistIds.has(playlist.id)}
                onChange={(isChecked) => onCheckboxChange(playlist.id, isChecked)}
              />
            ))}
          </div>
        )}
      </div>

      {/* 하단 버튼 - 한 줄 배치 */}
      <div className="flex gap-4 shrink-0">
        {/* 새 플레이리스트 버튼 */}
        <button
          onClick={onCreateNew}
          className="flex-1 h-[54px] bg-gray-700 rounded-xl px-5 py-3 hover:bg-gray-600 transition-colors"
        >
          <span className="text-body2-sb text-gray-50">+ 새 플레이리스트</span>
        </button>

        {/* 추가 버튼 */}
        <button
          onClick={onAddToPlaylists}
          disabled={adding || selectedPlaylistIds.size === 0}
          className="flex-1 h-[54px] bg-pink-600 rounded-xl px-5 py-3 hover:bg-pink-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <span className="text-body1-b text-white">
            {adding ? '추가 중...' : '추가'}
          </span>
        </button>
      </div>
    </div>
  );
}

interface PlaylistCheckboxItemProps {
  playlist: PlaylistDto;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

function PlaylistCheckboxItem({ playlist, checked, onChange }: PlaylistCheckboxItemProps) {
  return (
    <label className="flex items-center gap-2 py-2.5 px-1 cursor-pointer">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="w-5 h-5 rounded border-2 border-gray-600 bg-transparent checked:bg-pink-600 checked:border-pink-600 cursor-pointer appearance-none flex items-center justify-center after:content-['✓'] after:text-white after:text-sm after:hidden checked:after:block"
      />
      <div className="flex-1">
        <p className="text-body2-sb text-gray-100">{playlist.title}</p>
      </div>
    </label>
  );
}

interface CreatePlaylistViewProps {
  onBack: () => void;
  onCreate: (data: PlaylistCreateRequest) => Promise<void>;
}

function CreatePlaylistView({ onBack, onCreate }: CreatePlaylistViewProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);

  const handleSubmit = async () => {
    if (!title.trim() || !description.trim()) {
      toast.error('제목과 설명을 모두 입력해주세요.');
      return;
    }

    setCreating(true);
    try {
      await onCreate({ title: title.trim(), description: description.trim() });
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="flex flex-col gap-5">
      {/* 헤더 */}
      <div className="flex items-center gap-2 py-2">
        <button onClick={onBack} className="w-5 h-5">
          <img src={icArrowLeft} alt="뒤로가기" className="w-full h-full" />
        </button>
        <h2 className="text-title1-sb text-gray-300">새 플레이리스트</h2>
      </div>

      {/* 제목 입력 */}
      <div className="flex flex-col gap-2.5">
        <label className="text-body3-sb text-gray-300 px-1">제목</label>
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="제목을 입력해주세요"
          className="h-[54px] w-full bg-gray-800/50 border-[1.5px] border-gray-800 rounded-xl px-5 py-3.5 text-body2-m-140 text-gray-50 placeholder:text-gray-400 focus:outline-none focus:border-pink-600"
        />
      </div>

      {/* 설명 입력 */}
      <div className="flex flex-col gap-2.5">
        <label className="text-body3-sb text-gray-300 px-1">설명</label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="설명을 입력해주세요"
          className="h-[120px] w-full bg-gray-800/50 border-[1.5px] border-gray-800 rounded-xl px-5 py-4 text-body2-m-140 text-gray-50 placeholder:text-gray-400 focus:outline-none focus:border-pink-600 resize-none"
        />
      </div>

      {/* 버튼 */}
      <div className="flex gap-4 pt-1.5">
        <button
          onClick={onBack}
          disabled={creating}
          className="flex-1 h-[54px] bg-gray-700 rounded-xl text-body1-b text-gray-50 hover:bg-gray-600 transition-colors disabled:opacity-50"
        >
          취소
        </button>
        <button
          onClick={handleSubmit}
          disabled={creating || !title.trim() || !description.trim()}
          className="flex-1 h-[54px] bg-pink-600 rounded-xl text-body1-b text-white hover:bg-pink-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {creating ? '생성 중...' : '생성'}
        </button>
      </div>
    </div>
  );
}
