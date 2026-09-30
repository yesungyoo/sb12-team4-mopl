import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import icSearch from '@/assets/ic_search.svg';

interface SearchBarProps {
  onSearch: (value: string) => void;
  placeholder?: string;
  className?: string;
}

function useDebounce<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState<T>(value);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    return () => {
      clearTimeout(timer);
    };
  }, [value, delay]);

  return debouncedValue;
}

export default function SearchBar({
  onSearch,
  placeholder = 'Name, Email,etc...',
  className,
}: SearchBarProps) {
  const [value, setValue] = useState('');
  const debouncedValue = useDebounce(value, 300);

  useEffect(() => {
    onSearch(debouncedValue);
  }, [debouncedValue, onSearch]);

  return (
    <div
      className={cn(
        'flex h-[42px] w-[331px] items-center justify-between overflow-hidden rounded-full bg-gray-800/50 py-1 pl-5 pr-4',
        className
      )}
    >
      <input
        type="text"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={placeholder}
        className="flex-1 bg-transparent text-body3-m text-gray-100 placeholder:text-gray-400 focus:outline-none"
      />
      <img src={icSearch} alt="" className="size-6" />
    </div>
  );
}
