import type { ContentType } from '@/lib/types';

interface FilterTabsProps {
  selectedType: ContentType | 'ALL';
  onTypeChange: (type: ContentType | 'ALL') => void;
}

const FILTER_OPTIONS = [
  { label: '전체', value: 'ALL' as const },
  { label: '영화', value: 'movie' },
  { label: 'TV 시리즈', value: 'tvSeries' as const },
  { label: '스포츠', value: 'sport' as const },
] as const;

export default function FilterTabs({ selectedType, onTypeChange }: FilterTabsProps) {
  return (
    <div className="flex gap-[9px] items-center">
      {FILTER_OPTIONS.map((option) => (
        <button
          key={option.value}
          onClick={() => onTypeChange(option.value)}
          className={`
            px-3 py-1 rounded-full text-body3-b transition-colors
            ${
              selectedType === option.value
                ? 'bg-gray-100 text-gray-950'
                : 'bg-transparent text-gray-400 hover:text-gray-300'
            }
          `}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
