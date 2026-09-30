import { useEffect, useState } from 'react';

interface UseCountdownReturn {
  seconds: number;
  formatTime: () => string;
  isExpired: boolean;
  reset: (newSeconds?: number) => void;
}

/**
 * Countdown timer hook
 * @param initialSeconds - Initial countdown time in seconds
 * @returns Object with seconds, formatTime function, isExpired flag, and reset function
 * @example
 * const { seconds, formatTime, isExpired, reset } = useCountdown(180);
 * // formatTime() returns "03:00", "02:59", ... "00:00"
 */
export function useCountdown(initialSeconds: number): UseCountdownReturn {
  const [seconds, setSeconds] = useState(initialSeconds);

  useEffect(() => {
    if (seconds <= 0) return;

    const timer = setInterval(() => {
      setSeconds((s) => Math.max(0, s - 1));
    }, 1000);

    return () => clearInterval(timer);
  }, [seconds]);

  const formatTime = (): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
  };

  const reset = (newSeconds?: number): void => {
    setSeconds(newSeconds ?? initialSeconds);
  };

  return {
    seconds,
    formatTime,
    isExpired: seconds <= 0,
    reset,
  };
}
