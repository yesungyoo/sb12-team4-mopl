import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { Send } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
    createAiPlaylistSession,
    getAiPlaylistMessages,
    sendAiPlaylistMessage,
    type AiPlaylistMessage,
} from '@/lib/api/aiPlaylists';

interface AiPlaylistChatProps {
    initialSessionId: string | null;
}

export default function AiPlaylistChat({
                                           initialSessionId,
                                       }: AiPlaylistChatProps) {
    const [sessionId, setSessionId] = useState<string | null>(
        initialSessionId,
    );
    const [messages, setMessages] = useState<AiPlaylistMessage[]>([]);
    const [input, setInput] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [isHistoryLoading, setIsHistoryLoading] = useState(
        initialSessionId !== null,
    );
    const [pagination, setPagination] = useState<{
        nextCursor: string | null;
        nextIdAfter: string | null;
        hasNext: boolean;
    }>({ nextCursor: null, nextIdAfter: null, hasNext: false });
    const [isOlderLoading, setIsOlderLoading] = useState(false);
    const requestVersionRef = useRef(0);
    const olderLoadingRef = useRef(false);
    const scrollRef = useRef<HTMLDivElement>(null);
    const scrollAnchorRef = useRef<{ height: number; top: number } | null>(null);
    const [error, setError] = useState<string | null>(null);

    const messagesEndRef = useRef<HTMLDivElement>(null);

    useLayoutEffect(() => {
        const anchor = scrollAnchorRef.current;
        const scroll = scrollRef.current;
        if (anchor && scroll) {
            scroll.scrollTop = anchor.top + scroll.scrollHeight - anchor.height;
            scrollAnchorRef.current = null;
            return;
        }
        messagesEndRef.current?.scrollIntoView({
            behavior: 'smooth',
            block: 'end',
        });
    }, [messages, isLoading, isHistoryLoading]);

    useEffect(() => {
        setSessionId(initialSessionId);
        setMessages([]);
        setInput('');
        setError(null);
        const requestVersion = requestVersionRef;
        const version = ++requestVersion.current;
        setPagination({ nextCursor: null, nextIdAfter: null, hasNext: false });
        setIsOlderLoading(false);
        olderLoadingRef.current = false;
        scrollAnchorRef.current = null;
        setIsLoading(false);

        if (!initialSessionId) {
            setIsHistoryLoading(false);
            return () => {
                if (requestVersion.current === version) requestVersion.current++;
            };
        }

        let cancelled = false;

        const loadMessages = async () => {
            try {
                setIsHistoryLoading(true);

                const response = await getAiPlaylistMessages(
                    initialSessionId,
                    {
                        limit: 100,
                        sortDirection: 'DESCENDING',
                    },
                );

                if (!cancelled) {
                    const uniqueMessages = new Map(
                        response.data.map((message) => [message.id, message]),
                    );
                    setMessages([...uniqueMessages.values()].reverse());
                    setPagination(response);
                }
            } catch {
                if (!cancelled) {
                    setError('이전 대화를 불러오지 못했어요.');
                }
            } finally {
                if (!cancelled) {
                    setIsHistoryLoading(false);
                }
            }
        };

        void loadMessages();

        return () => {
            cancelled = true;
            if (requestVersion.current === version) requestVersion.current++;
        };
    }, [initialSessionId]);

    const loadOlderMessages = async () => {
        if (
            !sessionId || !pagination.hasNext || !pagination.nextCursor ||
            !pagination.nextIdAfter || olderLoadingRef.current
        ) {
            return;
        }

        const version = requestVersionRef.current;
        olderLoadingRef.current = true;
        setIsOlderLoading(true);
        setError(null);
        try {
            const response = await getAiPlaylistMessages(sessionId, {
                limit: 100,
                sortDirection: 'DESCENDING',
                cursor: pagination.nextCursor,
                idAfter: pagination.nextIdAfter,
            });
            if (version !== requestVersionRef.current) return;
            const scroll = scrollRef.current;
            if (scroll) {
                scrollAnchorRef.current = {
                    height: scroll.scrollHeight,
                    top: scroll.scrollTop,
                };
            }
            setMessages((previous) => {
                const seen = new Set(previous.map((message) => message.id));
                const older = response.data.filter((message) => {
                    if (seen.has(message.id)) return false;
                    seen.add(message.id);
                    return true;
                }).reverse();
                return [...older, ...previous];
            });
            setPagination(response);
        } catch {
            if (version === requestVersionRef.current) {
                setError('이전 메시지를 불러오지 못했어요. 다시 시도해주세요.');
            }
        } finally {
            if (version === requestVersionRef.current) {
                olderLoadingRef.current = false;
                setIsOlderLoading(false);
            }
        }
    };

    const handleSubmit = async (
        event: FormEvent<HTMLFormElement>,
    ) => {
        event.preventDefault();

        const message = input.trim();

        if (!message || isLoading || isHistoryLoading) {
            return;
        }

        const version = requestVersionRef.current;
        setInput('');
        setError(null);

        const optimisticMessage: AiPlaylistMessage = {
            id: `temp-user-${Date.now()}`,
            role: 'USER',
            content: message,
            createdAt: new Date().toISOString(),
        };

        setMessages((prev) => [...prev, optimisticMessage]);

        try {
            setIsLoading(true);

            let currentSessionId = sessionId;

            if (!currentSessionId) {
                currentSessionId = await createAiPlaylistSession();

                if (version !== requestVersionRef.current) return;
                setSessionId(currentSessionId);
            }

            const response = await sendAiPlaylistMessage(
                currentSessionId,
                message,
            );

            if (version !== requestVersionRef.current) return;

            const assistantMessage: AiPlaylistMessage = {
                id: `temp-assistant-${Date.now()}`,
                role: 'ASSISTANT',
                content: response.message,
                createdAt: new Date().toISOString(),
            };

            setMessages((prev) => [
                ...prev,
                assistantMessage,
            ]);
        } catch {
            if (version !== requestVersionRef.current) return;
            setError(
                '요청에 실패했어요. 잠시 후 다시 시도해주세요.',
            );
        } finally {
            if (version === requestVersionRef.current) setIsLoading(false);
        }
    };

    if (isHistoryLoading) {
        return (
            <div className="flex flex-1 items-center justify-center text-sm text-gray-400">
                이전 대화를 불러오는 중...
            </div>
        );
    }

    return (
        <div className="flex min-h-0 flex-1 flex-col">
            <div ref={scrollRef} className="ai-chat-scroll flex-1 overflow-y-auto p-4">
                {messages.length === 0 ? (
                    <div className="flex h-full items-center justify-center text-center">
                        <div>
                            <p className="font-medium text-white">
                                어떤 플레이리스트를 만들어볼까요?
                            </p>

                            <p className="mt-1 text-sm text-gray-400">
                                원하는 분위기나 콘텐츠를 말해보세요.
                            </p>
                        </div>
                    </div>
                ) : (
                    <div className="flex flex-col gap-3">
                        {pagination.hasNext && (
                            <Button
                                type="button"
                                variant="ghost"
                                disabled={isOlderLoading}
                                onClick={() => void loadOlderMessages()}
                                className="self-center text-gray-400"
                            >
                                {isOlderLoading ? '이전 메시지를 불러오는 중...' : '이전 메시지 더 보기'}
                            </Button>
                        )}
                        {messages.map((message) => (
                            <div
                                key={message.id}
                                className={
                                    message.role === 'USER'
                                        ? 'ml-auto max-w-[80%] rounded-2xl bg-pink-600 px-4 py-2 text-sm text-white'
                                        : 'mr-auto max-w-[80%] rounded-2xl bg-gray-800 px-4 py-2 text-sm text-gray-100'
                                }
                            >
                                {message.content}
                            </div>
                        ))}

                        {isLoading && (
                            <div className="mr-auto rounded-2xl bg-gray-800 px-4 py-2 text-sm text-gray-400">
                                답변을 만들고 있어요...
                            </div>
                        )}

                        <div ref={messagesEndRef} />
                    </div>
                )}
            </div>

            {error && (
                <p className="px-4 pb-2 text-sm text-red-400">
                    {error}
                </p>
            )}

            <form
                onSubmit={handleSubmit}
                className="flex gap-2 border-t border-gray-800 p-4"
            >
                <input
                    value={input}
                    onChange={(event) => setInput(event.target.value)}
                    placeholder="예: 비 오는 날 보기 좋은 영화"
                    maxLength={1000}
                    disabled={isLoading}
                    className="min-w-0 flex-1 rounded-xl bg-gray-800 px-4 py-2 text-sm text-white outline-none placeholder:text-gray-500"
                />

                <Button
                    type="submit"
                    size="icon"
                    variant="ghost"
                    disabled={!input.trim() || isLoading}
                    aria-label="메시지 전송"
                    className="
                        text-white
                        hover:bg-gray-700 hover:text-white
                        disabled:text-gray-500
                        disabled:opacity-100
                    "
                >
                    <Send className="h-5 w-5" />
                </Button>
            </form>
        </div>
    );
}