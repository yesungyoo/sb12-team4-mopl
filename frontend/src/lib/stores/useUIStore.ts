import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface UIState {
  sideMenuCollapsed: boolean;
  toggleSideMenu: () => void;
}

const useUIStore = create<UIState>()(
  persist(
    (set) => ({
      sideMenuCollapsed: false,
      toggleSideMenu: () =>
        set((state) => ({ sideMenuCollapsed: !state.sideMenuCollapsed })),
    }),
    {
      name: 'ui-storage',
    }
  )
);

export default useUIStore;
