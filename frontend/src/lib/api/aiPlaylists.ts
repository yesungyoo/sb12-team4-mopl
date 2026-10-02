import apiClient from './client';

export type AiPlaylistMessageRole = 'USER' | 'ASSISTANT';

export interface AiPlaylistSession {
    id: string;
    title: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface AiPlaylistMessage {
    id: string;
    role: AiPlaylistMessageRole;
    content: string;
    createdAt: string;
}

export interface AiPlaylistChatResponse {
    sessionId: string;
    message: string;
}

export interface CursorResponse<T> {
    data: T[];
    nextCursor: string | null;
    nextIdAfter: string | null;
    hasNext: boolean;
    totalCount: number;
    sortBy: string;
    sortDirection: string;
}

export interface AiPlaylistCursorParams {
    cursor?: string;
    idAfter?: string;
    limit?: number;
    sortDirection?: 'ASCENDING' | 'DESCENDING';
}

const BASE_URL = '/api/ai/playlists';

export const createAiPlaylistSession = async (): Promise<string> => {
    const response = await apiClient.post<string>(
        `${BASE_URL}/sessions`,
    );

    return response.data;
};

export const getAiPlaylistSessions = async (
    params?: AiPlaylistCursorParams,
): Promise<CursorResponse<AiPlaylistSession>> => {
    const response = await apiClient.get<
        CursorResponse<AiPlaylistSession>
    >(`${BASE_URL}/sessions`, {
        params,
    });

    return response.data;
};

export const getAiPlaylistMessages = async (
    sessionId: string,
    params?: AiPlaylistCursorParams,
): Promise<CursorResponse<AiPlaylistMessage>> => {
    const response = await apiClient.get<
        CursorResponse<AiPlaylistMessage>
    >(`${BASE_URL}/sessions/${sessionId}/messages`, {
        params,
    });

    return response.data;
};

export const sendAiPlaylistMessage = async (
    sessionId: string,
    message: string,
): Promise<AiPlaylistChatResponse> => {
    const response = await apiClient.post<AiPlaylistChatResponse>(
        `${BASE_URL}/sessions/${sessionId}/messages`,
        { message },
    );

    return response.data;
};