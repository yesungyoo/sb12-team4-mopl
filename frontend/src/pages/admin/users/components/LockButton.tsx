import { Loader2 } from 'lucide-react';
import icLock from '@/assets/ic_lock.svg';
import icUnLock from '@/assets/ic_unlock.svg';

interface LockButtonProps {
  locked: boolean;
  onClick: () => void;
  loading?: boolean;
  disabled?: boolean;
}

export default function LockButton({
  locked,
  onClick,
  loading = false,
  disabled = false,
}: LockButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading}
      className={`flex h-[34px] w-[68px] items-center justify-center gap-1 rounded-lg px-3 py-2 text-body3-b transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        locked
          ? 'bg-pink-500 text-white hover:bg-pink-600'
          : 'bg-gray-800 text-gray-200 hover:bg-gray-700'
      }`}
    >
      {loading ? (
        <Loader2 className="size-4 animate-spin" />
      ) : (
        <>
          <img src={locked ? icUnLock : icLock} alt="" className="size-4" />
          <span>{locked ? '해제' : '잠금'}</span>
        </>
      )}
    </button>
  );
}
