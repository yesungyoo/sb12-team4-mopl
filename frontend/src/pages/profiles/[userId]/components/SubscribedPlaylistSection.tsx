import PlaylistCardSkeleton from "@/pages/profiles/[userId]/components/PlaylistCardSkeleton.tsx";
import PlaylistCard from "@/pages/playlists/components/PlaylistCard.tsx";
import usePlaylistSubscriptionStore from "@/lib/stores/usePlaylistSubscriptionStore.ts";
import {useCallback, useEffect, useRef} from "react";

export default function SubscribedPlaylistSection({userId}: {userId: string}) {

  const {
    data: subscribedPlaylists,
    loading: subscribedPlaylistsLoading,
    updateParams: updateSubscribedPlaylistsParams,
    clear: clearSubscribedPlaylists,
    count: subscribedPlaylistsCount,
    hasNext: hasNextSubscribedPlaylists,
    fetchMore: fetchMoreSubscribedPlaylists,
  } = usePlaylistSubscriptionStore();

  // Local state
  const subscribedPlaylistsTotal = subscribedPlaylistsCount();

  // Refs for infinite scroll
  const subscribedPlaylistsScrollRef = useRef<HTMLDivElement>(null);

  // Fetch subscribed playlists
  useEffect(() => {
    if (!userId) return;

    updateSubscribedPlaylistsParams({
      subscriberIdEqual: userId,
    });

    return () => {
      clearSubscribedPlaylists();
    };
  }, [userId, updateSubscribedPlaylistsParams, clearSubscribedPlaylists]);

  // Infinite scroll handler for subscribed playlists
  const handleSubscribedPlaylistsScroll = useCallback(() => {
    const container = subscribedPlaylistsScrollRef.current;
    if (!container) return;

    const { scrollTop, scrollHeight, clientHeight } = container;
    const scrolledToBottom = scrollHeight - scrollTop - clientHeight < 100;

    if (scrolledToBottom && hasNextSubscribedPlaylists() && !subscribedPlaylistsLoading) {
      fetchMoreSubscribedPlaylists();
    }
  }, [hasNextSubscribedPlaylists, subscribedPlaylistsLoading, fetchMoreSubscribedPlaylists]);

  useEffect(() => {
    const subscribedContainer = subscribedPlaylistsScrollRef.current;
    if (subscribedContainer) {
      subscribedContainer.addEventListener('scroll', handleSubscribedPlaylistsScroll);
      return () => subscribedContainer.removeEventListener('scroll', handleSubscribedPlaylistsScroll);
    }
  }, [handleSubscribedPlaylistsScroll]);

  return (
      <>
        {/* Subscribed Playlists Section */}
        <section>
          <div className="flex items-center gap-2 mb-[20px]">
            <h2 className="text-header1-sb text-gray-50">구독 중인 플레이리스트</h2>
            <span className="text-header1-sb text-gray-500">{subscribedPlaylistsTotal}</span>
          </div>

          <div
              ref={subscribedPlaylistsScrollRef}
              className="h-[300px] py-1 pl-2 overflow-y-auto overflow-x-hidden pr-2 scrollbar-thin scrollbar-thumb-gray-700 scrollbar-track-gray-900"
          >
            {subscribedPlaylistsLoading && subscribedPlaylists.length === 0 ? (
                <div className="grid sm:grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-x-[30px] gap-y-[40px]">
                  {Array.from({ length: 6 }).map((_, index) => (
                      <PlaylistCardSkeleton key={index} />
                  ))}
                </div>
            ) : subscribedPlaylistsTotal === 0 ? (
                <div className="flex flex-col items-center justify-center h-full">
                  <p className="text-body1-m text-gray-400 mb-2">구독 중인 플레이리스트가 없습니다</p>
                </div>
            ) : (
                <div className="grid sm:grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-x-[30px] gap-y-[40px]">
                  {subscribedPlaylists.map((playlist) => (
                      <PlaylistCard key={playlist.id} playlist={playlist} />
                  ))}
                  {subscribedPlaylistsLoading && subscribedPlaylists.length > 0 && (
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
  )
}