import {create} from 'zustand';
import {useWebSocketStore} from './websocketStore';
import type {JwtDto} from '@/lib/types';
import {getCsrfToken, refreshToken, signIn, signOut} from '@/lib/api/auth';
import type {BaseStore} from './types';
import {execute} from "@/lib/stores/utils";
import {createBaseStoreActions} from "@/lib/stores/actions.ts";

interface AuthStore extends BaseStore<JwtDto, unknown> {
    signIn: (username: string, password: string) => Promise<void>;
    signOut: () => Promise<void>;
    isAuthenticated: () => boolean;
    getAccessToken: () => string | null;
}

export const useAuthStore = create<AuthStore>((set, get) => ({
    ...createBaseStoreActions({
        set, get,
        fetchApi: refreshToken,
    }),
    signIn: async (username: string, password: string) => {
        await execute(
            set,
            get,
            () => signIn({username, password}),
            {
                shouldThrow: true,
            }
        );

        await getCsrfToken();
    },

    signOut: async () => {
        await execute(
            set, get,
            signOut,
            {
                onSuccess: (_result, _set, get) => {
                    get().clear();
                    getCsrfToken();
                },
            }
        )
    },

    isAuthenticated: () => {
        const {data} = get();
        return data?.accessToken != null;
    },

    getAccessToken: () => {
        const {data} = get();
        return data?.accessToken || null;
    },
}));

// 로그인·로그아웃·인증 갱신의 모든 사용자 변경 경로에서 이전 연결을 정리한다.
useAuthStore.subscribe((state, previous) => {
    if (state.data?.userDto.id !== previous.data?.userDto.id) {
        useWebSocketStore.getState().disconnect();
    } else if (state.data?.accessToken && state.data.accessToken !== previous.data?.accessToken) {
        const client = useWebSocketStore.getState().stompClient;
        if (client) client.connectHeaders = {Authorization: `Bearer ${state.data.accessToken}`};
    }
});

export default useAuthStore;