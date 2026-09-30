import { useNavigate } from 'react-router-dom';
import type { PlaylistDto } from '@/lib/types';
import icStarFull from '@/assets/ic_star_full.svg';
import icClock from '@/assets/ic_clock.svg';

interface PlaylistCardProps {
  playlist: PlaylistDto;
}

export default function PlaylistCard({ playlist }: PlaylistCardProps) {
  const navigate = useNavigate();

  const handleClick = () => {
    navigate(`/playlists/${playlist.id}`);
  };

  // Get first 3 content thumbnails for display
  const thumbnails = playlist.contents.slice(0, 3);

  // Get content preview text (first 3 titles)
  const contentPreview = playlist.contents
    .slice(0, 3)
    .map((content) => content.title)
    .join(', ');

  // Format last updated time
  const formatUpdatedTime = (dateString: string) => {
    const date = new Date(dateString);
    const now = new Date();
    const diffInHours = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60));

    if (diffInHours < 1) return '방금 전';
    if (diffInHours < 24) return `${diffInHours}시간 전`;
    if (diffInHours < 48) return '어제';
    if (diffInHours < 168) return `${Math.floor(diffInHours / 24)}일 전`;

    return date.toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' });
  };

  // Get background image (first content thumbnail or fallback)
  const backgroundImage = thumbnails[0]?.thumbnailUrl || '';

  return (
    <div
      onClick={handleClick}
      className="relative w-full h-[250px] rounded-[24px] overflow-hidden cursor-pointer transition-transform hover:scale-[1.02] group"
      style={{
        backgroundImage: backgroundImage ? `url(${backgroundImage})` : undefined,
        backgroundSize: 'cover',
        backgroundPosition: 'center',
      }}
    >
      {/* Blurred overlay */}
      <div
        className="absolute inset-0 backdrop-blur-[25px]"
        style={{
          backgroundColor: 'rgba(12, 12, 14, 0.7)',
        }}
      />

      {/* Border */}
      <div
        className="absolute inset-0 rounded-[24px] pointer-events-none"
        style={{
          border: '1px solid rgba(255, 255, 255, 0.2)',
        }}
      />

      {/* Content */}
      <div className="relative h-full flex items-center px-[34px] py-8 gap-6">
        {/* Left side - Text content (flex-grow, can shrink) */}
        <div className="flex flex-col justify-between h-full min-w-0 flex-1">
          {/* Title */}
          <div className="min-w-0">
            <h3 className="text-header2-b text-white mb-5 truncate">
              {playlist.title}
            </h3>

            {/* Content preview */}
            <div className="flex items-baseline gap-1 overflow-hidden min-w-0">
              <span className="text-body1-b text-gray-200 truncate">
                {contentPreview}
              </span>
              {playlist.contents.length > 3 && (
                <span className="text-body1-m text-gray-300 flex-shrink-0">등</span>
              )}
            </div>

            {/* Content count */}
            <p className="text-body2-sb text-gray-500 mt-2">
              {playlist.contents.length}개의 콘텐츠
            </p>
          </div>

          {/* Metadata row */}
          <div className="flex items-center gap-[6px] text-gray-400">
            {/* Subscriber count */}
            <div className="flex items-center gap-[2px]">
              <img src={icStarFull} alt="" className="w-4 h-4" />
              <span className="text-caption1-m">{playlist.subscriberCount}명 구독</span>
            </div>

            {/* Separator */}
            <span className="text-caption1-sb">∙</span>

            {/* Last updated */}
            <div className="flex items-center gap-[2px]">
              <img src={icClock} alt="" className="w-4 h-4" />
              <span className="text-caption1-m">{formatUpdatedTime(playlist.updatedAt)}</span>
            </div>
          </div>
        </div>

        {/* Right side - Stacked thumbnails */}
        {thumbnails.length > 0 && (
          <div className="relative flex-shrink-0 w-[130px] h-[190px] self-center">
            {/* Stack of 3 thumbnails - center-aligned, stacked upwards with decreasing size */}

            {/* Back thumbnail (3rd) - smallest, on top */}
            {thumbnails[2]?.thumbnailUrl && (
              <div
                className="absolute top-[-8px] left-1/2 -translate-x-1/2 w-[106px] h-[156px] rounded-[10px] overflow-hidden"
                style={{
                  border: '1px solid rgba(255, 255, 255, 0.1)',
                }}
              >
                <div className="absolute inset-0 bg-black opacity-80" />
                <img
                  src={thumbnails[2].thumbnailUrl}
                  alt=""
                  className="w-full h-full object-cover"
                />
              </div>
            )}

            {/* Middle thumbnail (2nd) - medium size, middle layer */}
            {thumbnails[1]?.thumbnailUrl && (
              <div
                className="absolute top-[-4px] left-1/2 -translate-x-1/2 w-[118px] h-[173px] rounded-[11px] overflow-hidden"
                style={{
                  border: '1px solid rgba(255, 255, 255, 0.1)',
                }}
              >
                <div className="absolute inset-0 bg-black opacity-50" />
                <img
                  src={thumbnails[1].thumbnailUrl}
                  alt=""
                  className="w-full h-full object-cover"
                />
              </div>
            )}

            {/* Front thumbnail (1st) - largest, bottom layer */}
            <div
              className="absolute top-0 left-1/2 -translate-x-1/2 w-[130px] h-[190px] rounded-[16px] overflow-hidden"
              style={{
                border: '1px solid rgba(255, 255, 255, 0.1)',
              }}
            >
              <img
                src={thumbnails[0].thumbnailUrl || ''}
                alt={thumbnails[0].title}
                className="w-full h-full object-cover"
              />

              {/* Count badge if more than 3 contents */}
              {playlist.contents.length > 3 && (
                <div
                  className="absolute bottom-2 right-2 px-3 py-1 rounded-full"
                  style={{
                    backgroundColor: 'rgba(12, 12, 14, 0.7)',
                  }}
                >
                  <span className="text-caption1-sb text-gray-100">
                    +{playlist.contents.length - 3}
                  </span>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
