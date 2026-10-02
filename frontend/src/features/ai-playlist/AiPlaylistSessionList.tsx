import { useEffect, useRef, useState } from 'react';
import { MessageCircle } from 'lucide-react';

import {
    getAiPlaylistSessions,
    type AiPlaylistSession,
} from '@/lib/api/aiPlaylists';

interface AiPlaylistSessionListProps {
    onSelect: (sessionId: string) => void;
}

const formatUpdatedAt = (value: string) => {
    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return '';
    }

    const now = new Date();

    const isToday =
        date.getFullYear() === now.getFullYear() &&
        date.getMonth() === now.getMonth() &&
        date.getDate() === now.getDate();

    if (isToday) {
        return new Intl.DateTimeFormat('ko-KR', {
            hour: '2-digit',
            minute: '2-digit',
        }).format(date);
    }

    return new Intl.DateTimeFormat('ko-KR', {
        month: 'long',
        day: 'numeric',
    }).format(date);
};

export default function AiPlaylistSessionList({
                                                  onSelect,
                                              }: AiPlaylistSessionListProps) {
    const [sessions, setSessions] = useState<AiPlaylistSession[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [pagination, setPagination] = useState<{
        nextCursor: string | null;
        nextIdAfter: string | null;
        hasNext: boolean;
    }>({ nextCursor: null, nextIdAfter: null, hasNext: false });
    const [isMoreLoading, setIsMoreLoading] = useState(false);
    const requestStateRef = useRef({ version: 0, loading: false });
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        const requestState = requestStateRef.current;
        const version = ++requestState.version;
        requestState.loading = false;
        setSessions([]);
        setPagination({ nextCursor: null, nextIdAfter: null, hasNext: false });
        setIsMoreLoading(false);

        const loadSessions = async () => {
            try {
                setIsLoading(true);
                setError(null);

                const response = await getAiPlaylistSessions({
                    limit: 20,
                    sortDirection: 'DESCENDING',
                });

                if (!cancelled) {
                    setSessions([...new Map(
                        response.data.map((session) => [session.id, session]),
                    ).values()]);
                    setPagination(response);
                }
            } catch {
                if (!cancelled) {
                    setError('대화 목록을 불러오지 못했어요.');
                }
            } finally {
                if (!cancelled) {
                    setIsLoading(false);
                }
            }
        };

        void loadSessions();

        return () => {
            cancelled = true;
            if (requestState.version === version) requestState.version++;
        };
    }, []);

    const loadMoreSessions = async () => {
        const requestState = requestStateRef.current;
        if (
            isLoading || requestState.loading || !pagination.hasNext ||
            !pagination.nextCursor || !pagination.nextIdAfter
        ) {
            return;
        }

        const version = requestState.version;
        requestState.loading = true;
        setIsMoreLoading(true);
        setError(null);
        try {
            const response = await getAiPlaylistSessions({
                limit: 20,
                sortDirection: 'DESCENDING',
                cursor: pagination.nextCursor,
                idAfter: pagination.nextIdAfter,
            });
            if (requestState.version !== version) return;
            setSessions((previous) => {
                const seen = new Set(previous.map((session) => session.id));
                const additional = response.data.filter((session) => {
                    if (seen.has(session.id)) return false;
                    seen.add(session.id);
                    return true;
                });
                return [...previous, ...additional];
            });
            setPagination(response);
        } catch {
            if (requestState.version === version) {
                setError('대화를 더 불러오지 못했어요. 다시 시도해주세요.');
            }
        } finally {
            if (requestState.version === version) {
                requestState.loading = false;
                setIsMoreLoading(false);
            }
        }
    };

    if (isLoading) {
        return (
            <div className="flex flex-1 items-center justify-center text-sm text-gray-400">
                대화 목록을 불러오는 중...
            </div>
        );
    }

    if (error && sessions.length === 0) {
        return (
            <div className="flex flex-1 items-center justify-center px-6 text-center text-sm text-red-400">
                {error}
            </div>
        );
    }

    if (sessions.length === 0) {
        return (
            <div className="flex flex-1 items-center justify-center px-6 text-center">
                <div>
                    <MessageCircle className="mx-auto mb-3 h-8 w-8 text-gray-500" />

                    <p className="font-medium text-white">
                        아직 이전 대화가 없어요
                    </p>

                    <p className="mt-1 text-sm text-gray-400">
                        + 버튼을 눌러 새로운 대화를 시작해보세요.
                    </p>
                </div>
            </div>
        );
    }

    return (
        <div className="ai-chat-scroll min-h-0 flex-1 overflow-y-auto p-3">
            <div className="flex flex-col gap-1">
                {sessions.map((session) => (
                    <button
                        key={session.id}
                        type="button"
                        onClick={() => onSelect(session.id)}
                        className="
                            flex w-full items-center gap-3 rounded-xl
                            px-3 py-3 text-left
                            transition-colors hover:bg-gray-800
                        "
                    >
                        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gray-800">
                            <MessageCircle className="h-5 w-5 text-pink-500" />
                        </div>

                        <div className="min-w-0 flex-1">
                            <p className="truncate text-sm font-medium text-white">
                                {session.title?.trim() || '새로운 대화'}
                            </p>

                            <p className="mt-1 text-xs text-gray-500">
                                {formatUpdatedAt(session.updatedAt)}
                            </p>
                        </div>
                    </button>
                ))}
                {error && (
                    <p role="alert" className="px-3 py-2 text-center text-sm text-red-400">
                        {error}
                    </p>
                )}
                {pagination.hasNext && (
                    <button
                        type="button"
                        onClick={() => void loadMoreSessions()}
                        disabled={isMoreLoading}
                        className="rounded-xl px-3 py-3 text-sm text-gray-400 hover:bg-gray-800 disabled:opacity-50"
                    >
                        {isMoreLoading ? '대화를 불러오는 중...' : '대화 더 보기'}
                    </button>
                )}
            </div>
        </div>
    );
}