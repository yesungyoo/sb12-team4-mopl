import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { SortDirection } from '@/lib/types';

type SortBy = 'name' | 'email' | 'createdAt' | 'isLocked' | 'role';

interface SortableHeaderProps {
  label: string;
  sortKey: SortBy;
  currentSortBy?: SortBy;
  currentSortDirection?: SortDirection;
  onSort: (sortBy: SortBy) => void;
  className?: string;
}

export default function SortableHeader({
  label,
  sortKey,
  currentSortBy,
  currentSortDirection,
  onSort,
  className,
}: SortableHeaderProps) {
  const isActive = currentSortBy === sortKey;

  const getSortIcon = () => {
    if (!isActive) {
      return <ArrowUpDown className="size-4 text-gray-600" />;
    }
    if (currentSortDirection === 'ASCENDING') {
      return <ArrowUp className="size-4 text-pink-500" />;
    }
    return <ArrowDown className="size-4 text-pink-500" />;
  };

  return (
    <button
      type="button"
      onClick={() => onSort(sortKey)}
      className={cn(
        'flex items-center gap-1 text-body1-sb transition-colors hover:text-gray-100',
        isActive ? 'text-gray-200' : 'text-gray-400',
        className
      )}
    >
      <span>{label}</span>
      {getSortIcon()}
    </button>
  );
}
