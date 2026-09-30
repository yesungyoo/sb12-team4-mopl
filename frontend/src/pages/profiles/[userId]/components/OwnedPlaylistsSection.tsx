import PlaylistCard from "@/pages/playlists/components/PlaylistCard";
import usePlaylistStore from "@/lib/stores/usePlaylistStore";
import {useCallback, useEffect, useRef} from "react";
import PlaylistCardSkeleton from "./PlaylistCardSkeleton";


export default function OwnedPlaylistsSection({userId}: {userId: string}) {
  const {
    data: ownedPlaylists,
    loading: ownedPlaylistsLoading,
    updateParams: updateOwnedPlaylistsParams,
    clear: clearOwnedPlaylists,
    count: ownedPlaylistsCount,
    hasNext: hasNextOwnedPlaylists,
    fetchMore: fetchMoreOwnedPlaylists,
  } = usePlaylistStore();

  const ownedPlaylistsTotal = ownedPlaylistsCount();

  const ownedPlaylistsScrollRef = useRef<HTMLDivElement>(null);

  // Fetch owned playlists
  useEffect(() => {
    if (!userId) return;

    updateOwnedPlaylistsParams({
      ownerIdEqual: userId,
    });

    return () => {
      clearOwnedPlaylists();
    };
  }, [userId, updateOwnedPlaylistsParams, clearOwnedPlaylists]);

  // Infinite scroll handler for owned playlists
  const handleOwnedPlaylistsScroll = useCallback(() => {
    const container = ownedPlaylistsScrollRef.current;
    if (!container) return;

    const { scrollTop, scrollHeight, clientHeight } = container;
    const scrolledToBottom = scrollHeight - scrollTop - clientHeight < 100;

    if (scrolledToBottom && hasNextOwnedPlaylists() && !ownedPlaylistsLoading) {
      fetchMoreOwnedPlaylists();
    }
  }, [hasNextOwnedPlaylists, ownedPlaylistsLoading, fetchMoreOwnedPlaylists]);

  // Attach scroll listeners
  useEffect(() => {
    const ownedContainer = ownedPlaylistsScrollRef.current;
    if (ownedContainer) {
      ownedContainer.addEventListener('scroll', handleOwnedPlaylistsScroll);
      return () => ownedContainer.removeEventListener('scroll', handleOwnedPlaylistsScroll);
    }
  }, [handleOwnedPlaylistsScroll]);

  return (
    <>
      {/* Owned Playlists Section */}
      <section className="mb-[60px]">
        <div className="flex items-center gap-2 mb-[20px]">
          <h2 className="text-header1-sb text-gray-50">플레이리스트</h2>
          <span className="text-header1-sb text-gray-500">{ownedPlaylistsTotal}</span>
        </div>

        <div
            ref={ownedPlaylistsScrollRef}
            className="h-[300px] py-1 pl-2 overflow-y-auto overflow-x-hidden pr-2 scrollbar-thin scrollbar-thumb-gray-700 scrollbar-track-gray-900"
        >
          {ownedPlaylistsLoading && ownedPlaylists.length === 0 ? (
              <div className="grid sm:grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-x-[30px] gap-y-[40px]">
                {Array.from({ length: 6 }).map((_, index) => (
                    <PlaylistCardSkeleton key={index} />
                ))}
              </div>
          ) : ownedPlaylistsTotal === 0 ? (
              <div className="flex flex-col items-center justify-center h-full">
                <p className="text-body1-m text-gray-400 mb-2">플레이리스트가 없습니다</p>
              </div>
          ) : (
              <div className="grid sm:grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-x-[30px] gap-y-[40px]">
                {ownedPlaylists.map((playlist) => (
                    <PlaylistCard key={playlist.id} playlist={playlist} />
                ))}
                {ownedPlaylistsLoading && ownedPlaylists.length > 0 && (
                    <>
                      {Array.from({ length: 3 }).map((_, index) => (
                          <PlaylistCardSkeleton key={`skeleton-${index}`} />
                      ))}
                    </>
                )}
              </div>
          )}
        </div>
      </section>
    </>
  );
}