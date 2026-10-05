import type { ContentType } from '@/lib/types';

export type ContentsTab = 'home' | 'new' | ContentType;

interface FilterTabsProps {
  selectedTab: ContentsTab;
  onTabChange: (tab: ContentsTab) => void;
}

const TAB_OPTIONS: {
  label: string;
  value: ContentsTab;
}[] = [
  { label: '홈', value: 'home' },
  { label: '신규', value: 'new' },
  { label: '영화', value: 'movie' },
  { label: 'TV 시리즈', value: 'tvSeries' },
  { label: '스포츠', value: 'sport' },
];

export default function FilterTabs({
  selectedTab,
  onTabChange,
}: FilterTabsProps) {
  return (
    <div className="flex flex-wrap items-center gap-2.5">
      {TAB_OPTIONS.map((option) => {
        const selected = selectedTab === option.value;

        return (
          <button
            key={option.value}
            type="button"
            onClick={() => onTabChange(option.value)}
            className={[
              'rounded-full px-5 py-2.5 text-body2-sb transition-colors',
              selected
                ? 'bg-pink-500 text-white'
                : 'bg-gray-800 text-gray-300 hover:bg-gray-700 hover:text-white',
            ].join(' ')}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}