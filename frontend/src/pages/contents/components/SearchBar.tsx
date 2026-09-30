import { useState, useEffect } from 'react';
import icSearch from '@/assets/ic_search.svg';

interface SearchBarProps {
  onSearch: (query: string) => void;
  placeholder?: string;
}

export default function SearchBar({ onSearch, placeholder = '검색어를 입력하세요' }: SearchBarProps) {
  const [value, setValue] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => {
      onSearch(value);
    }, 300);

    return () => clearTimeout(timer);
  }, [value, onSearch]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      onSearch(value);
    }
  };

  return (
    <div className="relative w-[331px]">
      <input
        type="text"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        className="
          w-full h-[42px] pl-5 pr-12 py-1 rounded-full
          bg-gray-800/50 text-body3-m text-white
          placeholder:text-gray-400
          focus:outline-none focus:ring-2 focus:ring-gray-700
          transition-all
        "
      />
      <img
        src={icSearch}
        alt=""
        className="absolute right-4 top-1/2 -translate-y-1/2 w-6 h-6 pointer-events-none"
      />
    </div>
  );
}
