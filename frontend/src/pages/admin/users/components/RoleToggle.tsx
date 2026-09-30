import { cn } from '@/lib/utils';

interface RoleToggleProps {
  isAdmin: boolean;
  onChange: (isAdmin: boolean) => void;
  loading?: boolean;
  disabled?: boolean;
}

export default function RoleToggle({
  isAdmin,
  onChange,
  loading = false,
  disabled = false,
}: RoleToggleProps) {
  return (
    <button
      type="button"
      onClick={() => onChange(!isAdmin)}
      disabled={disabled || loading}
      className={cn(
        'relative h-[25px] w-[50px] rounded-full border border-white/10 transition-colors disabled:cursor-not-allowed disabled:opacity-50',
        isAdmin ? 'bg-pink-600' : 'bg-gray-800'
      )}
      aria-label={isAdmin ? 'Admin role' : 'User role'}
      aria-checked={isAdmin}
      role="switch"
    >
      <div
        className={cn(
          'absolute top-1/2 size-[18px] -translate-y-1/2 rounded-full border border-white/10 bg-gray-300 transition-all duration-200',
          isAdmin ? 'left-[28px]' : 'left-1'
        )}
      />
    </button>
  );
}
