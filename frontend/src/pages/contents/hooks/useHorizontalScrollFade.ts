import {
  useCallback,
  useEffect,
  useState,
  type RefObject,
} from 'react';

export default function useHorizontalScrollFade<T extends HTMLElement>(
  scrollContainerRef: RefObject<T | null>,
  refreshKey?: unknown,
) {
  const [showLeftFade, setShowLeftFade] = useState(false);
  const [showRightFade, setShowRightFade] = useState(false);

  const updateScrollFade = useCallback(() => {
    const element = scrollContainerRef.current;

    if (!element) {
      setShowLeftFade(false);
      setShowRightFade(false);
      return;
    }

    const maxScrollLeft = element.scrollWidth - element.clientWidth;
    const hasOverflow = maxScrollLeft > 1;

    setShowLeftFade(
      hasOverflow
      && element.scrollLeft > 1,
    );

    setShowRightFade(
      hasOverflow
      && element.scrollLeft < maxScrollLeft - 1,
    );
  }, [scrollContainerRef]);

  useEffect(() => {
    const element = scrollContainerRef.current;

    if (!element) {
      return;
    }

    const animationFrameId = window.requestAnimationFrame(
      updateScrollFade,
    );

    element.addEventListener(
      'scroll',
      updateScrollFade,
      {
        passive: true,
      },
    );

    const resizeObserver = new ResizeObserver(
      updateScrollFade,
    );

    resizeObserver.observe(element);

    window.addEventListener(
      'resize',
      updateScrollFade,
    );

    return () => {
      window.cancelAnimationFrame(animationFrameId);

      element.removeEventListener(
        'scroll',
        updateScrollFade,
      );

      resizeObserver.disconnect();

      window.removeEventListener(
        'resize',
        updateScrollFade,
      );
    };
  }, [
    scrollContainerRef,
    refreshKey,
    updateScrollFade,
  ]);

  return {
    showLeftFade,
    showRightFade,
  };
}