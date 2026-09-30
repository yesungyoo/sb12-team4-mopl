import icProfileDefault from "@/assets/ic_profile_default.svg";
import {Input} from "@/components/ui/input.tsx";
import {cn} from "@/lib/utils.ts";
import {Button} from "@/components/ui/button.tsx";
import {LoadingSpinner} from "@/components/ui/loading-spinner.tsx";
import {
  cancelFollow,
  createFollow, getFollowerCount,
  getWatchingSessionByWatcher,
  isFollowedByMe,
  updateUser
} from "@/lib/api";
import {toast} from "sonner";
import {useEffect, useRef, useState} from "react";
import type {WatchingSessionDto} from "@/lib/types";
import {useNavigate} from "react-router-dom";
import useUserProfileStore from "@/lib/stores/useUserProfileStore.ts";
import useAuthStore from "@/lib/stores/useAuthStore.ts";

export default function UserProfileSection({userId}: {userId: string}) {
  const navigate = useNavigate();

  const { data: profile, loading: profileLoading, error, updateParams, clear } = useUserProfileStore();
  const { data: jwt } = useAuthStore();

  const [currentlyWatching, setCurrentlyWatching] = useState<WatchingSessionDto | null>(null);
  const [watchingLoading, setWatchingLoading] = useState(false);
  const [isFollowing, setIsFollowing] = useState(false);
  const [followLoading, setFollowLoading] = useState(false);
  const [followerCount, setFollowerCount] = useState(0);
  const [followId, setFollowId] = useState<string | null>(null);

  // Edit mode state
  type ProfileMode = 'view' | 'edit';
  const [mode, setMode] = useState<ProfileMode>('view');
  const [editName, setEditName] = useState('');
  const [editImageFile, setEditImageFile] = useState<File | null>(null);
  const [editImagePreview, setEditImagePreview] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Check if viewing own profile
  const isOwnProfile = profile && jwt ? profile.id === jwt.userDto.id : false;

  // Fetch user profile
  useEffect(() => {
    updateParams({ userId });

    return () => {
      clear();
    };
  }, [userId, updateParams, clear]);

  // Fetch currently watching content
  useEffect(() => {
    const fetchWatchingSession = async () => {
      setWatchingLoading(true);
      try {
        const session = await getWatchingSessionByWatcher(userId);
        setCurrentlyWatching(session);
      } catch (error) {
        console.error('Failed to fetch watching session:', error);
      } finally {
        setWatchingLoading(false);
      }
    };

    fetchWatchingSession();
  }, [userId]);

  // Handle follow/unfollow
  const handleFollowToggle = async () => {
    if (!userId || followLoading) return;

    setFollowLoading(true);
    try {
      if (isFollowing) {
        // Unfollow
        if (followId) {
          await cancelFollow(followId);
          setIsFollowing(false);
          setFollowId(null);
          setFollowerCount((prev) => prev - 1);
          toast.success('언팔로우했습니다');
        }
      } else {
        // Follow
        const result = await createFollow({ followeeId: userId });
        setFollowId(result.id);
        setIsFollowing(true);
        setFollowerCount((prev) => prev + 1);
        toast.success('팔로우했습니다');
      }
    } catch (error) {
      console.error('Failed to toggle follow:', error);
      toast.error(isFollowing ? '언팔로우에 실패했습니다' : '팔로우에 실패했습니다');
    } finally {
      setFollowLoading(false);
    }
  };


  // Handle enter edit mode
  const handleEnterEditMode = () => {
    if (!profile) return;
    setMode('edit');
    setEditName(profile.name);
    setEditImagePreview(profile.profileImageUrl || null);
    setEditImageFile(null);
  };

  // Handle cancel edit
  const handleCancelEdit = () => {
    setMode('view');
    setEditName('');
    setEditImageFile(null);
    setEditImagePreview(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  // Handle image file selection
  const handleImageChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    // Validate file type
    if (!file.type.startsWith('image/')) {
      toast.error('이미지 파일만 업로드할 수 있습니다');
      return;
    }

    setEditImageFile(file);

    // Create preview URL
    const reader = new FileReader();
    reader.onloadend = () => {
      setEditImagePreview(reader.result as string);
    };
    reader.readAsDataURL(file);
  };


  // Handle save profile
  const handleSaveProfile = async () => {
    if (!profile || !userId) return;

    // Validate name
    if (editName.trim().length < 2) {
      toast.error('이름은 최소 2자 이상이어야 합니다');
      return;
    }

    setIsUpdating(true);
    try {
      // 1. Call API directly
      const updatedUser = await updateUser(
          userId,
          { name: editName.trim() },
          editImageFile || undefined
      );

      // 2. Sync stores
      useUserProfileStore.getState().update(updatedUser);

      // Sync auth store if editing own profile
      if (isOwnProfile && jwt) {
        useAuthStore.getState().update({
          userDto: updatedUser,
        });
      }

      // 3. Success handling
      toast.success('프로필이 업데이트되었습니다');
      setMode('view');
      setEditImageFile(null);
      setEditImagePreview(null);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    } catch (error) {
      console.error('Failed to update profile:', error);
      toast.error('프로필 업데이트에 실패했습니다');
    } finally {
      setIsUpdating(false);
    }
  };

  // Fetch follow status and follower count
  useEffect(() => {
    if (!userId || isOwnProfile) return;

    const fetchFollowStatus = async () => {
      try {
        const [follow, count] = await Promise.all([
          isFollowedByMe(userId),
          getFollowerCount(userId),
        ]);
        setIsFollowing(follow !== null);
        setFollowId(follow?.id ?? null);
        setFollowerCount(count);
      } catch (error) {
        console.error('Failed to fetch follow status:', error);
      }
    };

    fetchFollowStatus();
  }, [userId, isOwnProfile]);

  // Handle send message (placeholder)
  const handleSendMessage = () => {
    navigate(`/conversations/with?userId=${userId}`);
  };

  // Handle content click
  const handleContentClick = (contentId: string) => {
    navigate(`/contents/${contentId}`);
  };

  // Loading state
  if (profileLoading) {
    return (
        <div className="flex items-center justify-center min-h-screen">
          <LoadingSpinner />
        </div>
    );
  }

  // Error state
  if (error || !profile) {
    return (
        <div className="flex flex-col items-center justify-center min-h-screen gap-4">
          <p className="text-body1-m text-gray-300">{error || '사용자를 찾을 수 없습니다'}</p>
          <Button onClick={() => navigate(-1)}>뒤로 가기</Button>
        </div>
    );
  }

  return (
      <>
        {/* Profile Header */}
        <div className="flex items-center gap-6 mb-[60px]">
          {/* Avatar */}
          <div className="relative size-[110px] rounded-full overflow-hidden bg-gray-700 border border-gray-800 shrink-0">
            {mode === 'edit' ? (
                <>
                  <img
                      src={editImagePreview || icProfileDefault}
                      alt={editName}
                      className="size-full object-cover"
                  />
                  <label
                      htmlFor="profile-image-input"
                      className="absolute inset-0 flex items-center justify-center bg-black/50 cursor-pointer hover:bg-black/60 transition-colors"
                  >
                    <span className="text-body3-sb text-white">변경</span>
                  </label>
                  <input
                      ref={fileInputRef}
                      id="profile-image-input"
                      type="file"
                      accept="image/*"
                      onChange={handleImageChange}
                      className="hidden"
                  />
                </>
            ) : (
                <img
                    src={profile.profileImageUrl || icProfileDefault}
                    alt={profile.name}
                    className="size-full object-cover"
                />
            )}
          </div>

          {/* Profile Info */}
          <div className="flex flex-col gap-4">
            {/* Name and Action Buttons */}
            <div className="flex items-center gap-[14px]">
              {mode === 'edit' ? (
                  <>
                    <Input
                        value={editName}
                        onChange={(e) => setEditName(e.target.value)}
                        placeholder="이름 입력"
                        className={cn(
                            'h-[42px] w-[280px] rounded-xl border-[1.5px]',
                            'border-gray-700 bg-gray-800/50 px-4 text-body1-m text-white',
                            'placeholder:text-gray-400 focus:border-pink-500'
                        )}
                    />
                    <div className="flex items-center gap-[10px]">
                      <Button
                          onClick={handleSaveProfile}
                          disabled={isUpdating || editName.trim().length < 2}
                          className="h-[34px] px-3 bg-pink-500 hover:bg-pink-600 disabled:bg-gray-800 disabled:text-gray-600"
                      >
                      <span className="text-body3-b text-white">
                        {isUpdating ? '저장 중...' : '저장'}
                      </span>
                      </Button>
                      <Button
                          onClick={handleCancelEdit}
                          disabled={isUpdating}
                          className="h-[34px] px-3 border border-gray-700 bg-gray-900 hover:bg-gray-800 text-white disabled:opacity-50"
                      >
                        <span className="text-body3-b">취소</span>
                      </Button>
                    </div>
                  </>
              ) : (
                  <>
                    <h1 className="text-header1-sb text-gray-50">{profile.name}</h1>

                    {/* Conditional Buttons */}
                    {isOwnProfile ? (
                        // Own profile - Edit button only
                        <Button
                            onClick={handleEnterEditMode}
                            className="h-[34px] px-3 border border-gray-700 bg-gray-900 hover:bg-gray-800 text-white"
                        >
                          <span className="text-body3-b">수정</span>
                        </Button>
                    ) : (
                        // Other's profile - Follow and Message buttons
                        <div className="flex items-center gap-[10px]">
                          <Button
                              onClick={handleFollowToggle}
                              disabled={followLoading}
                              className={
                                isFollowing
                                    ? 'h-[34px] px-3 bg-gray-800 hover:bg-gray-700'
                                    : 'h-[34px] px-3 bg-pink-500 hover:bg-pink-600'
                              }
                          >
                        <span className="text-body3-b text-white">
                          {followLoading ? '...' : isFollowing ? '팔로잉' : '팔로우'}
                        </span>
                          </Button>
                          <Button
                              variant="secondary"
                              onClick={handleSendMessage}
                              className="h-[34px] px-3 bg-gray-700 hover:bg-gray-600"
                          >
                            <span className="text-body3-b">메시지 보내기</span>
                          </Button>
                        </div>
                    )}
                  </>
              )}
            </div>

            {/* Follower Stats */}
            <div className="flex items-center gap-1">
              <span className="text-body1-m text-gray-300">팔로우</span>
              <span className="text-title1-sb text-gray-100">{followerCount.toLocaleString()}</span>
            </div>

            {/* Currently Watching (if exists) */}
            {watchingLoading ? (
                <div className="flex items-center gap-2">
                  <LoadingSpinner />
                  <span className="text-body2-m text-gray-400">시청 정보 불러오는 중...</span>
                </div>
            ) : (
                currentlyWatching && (
                    <p className="text-body2-m">
                      <span className="text-gray-400">지금 </span>
                      <button
                          onClick={() => handleContentClick(currentlyWatching.content.id)}
                          className="text-gray-100 font-bold underline hover:text-gray-50 transition-colors cursor-pointer"
                      >
                        {currentlyWatching.content.title}
                      </button>
                      <span className="text-gray-400">를 보고 있습니다.</span>
                    </p>
                )
            )}
          </div>
        </div>
      </>
  )
}