import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { FindPlaylistsParams, SortDirection } from '@/lib/types';

export type SortOption = {
  label: string;
  sortBy: FindPlaylistsParams['sortBy'];
  sortDirection?: SortDirection;
};

interface PlaylistSortDropdownProps {
  value: string;
  onValueChange: (option: SortOption) => void;
}

const SORT_OPTIONS: (SortOption & { value: string })[] = [
  { value: 'latest', label: '최신순', sortBy: 'updatedAt', sortDirection: 'DESCENDING' },
  { value: 'popular', label: '구독순', sortBy: 'subscribeCount', sortDirection: 'DESCENDING' },
];

export default function PlaylistSortDropdown({
  value,
  onValueChange,
}: PlaylistSortDropdownProps) {
  const handleValueChange = (selectedValue: string) => {
    const option = SORT_OPTIONS.find((opt) => opt.value === selectedValue);
    if (option) {
      onValueChange(option);
    }
  };

  return (
    <Select value={value} onValueChange={handleValueChange}>
      <SelectTrigger className="w-[93px] h-11 bg-gray-800 border-gray-700 text-body3-m text-gray-300">
        <SelectValue />
      </SelectTrigger>
      <SelectContent className="bg-gray-800 border-gray-700">
        {SORT_OPTIONS.map((option) => (
          <SelectItem
            key={option.value}
            value={option.value}
            className="text-body3-m text-gray-300 focus:bg-gray-700 focus:text-white"
          >
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
