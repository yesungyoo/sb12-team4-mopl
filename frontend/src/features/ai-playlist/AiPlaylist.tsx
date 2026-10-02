import { useState } from 'react';
import { ArrowLeft, Bot, Menu, Plus, X } from 'lucide-react';

import { Button } from '@/components/ui/button';

import AiPlaylistChat from './AiPlaylistChat';
import AiPlaylistSessionList from './AiPlaylistSessionList';

type View = 'chat' | 'list';

export default function AiPlaylist() {
    const [isOpen, setIsOpen] = useState(false);
    const [view, setView] = useState<View>('chat');
    const [selectedSessionId, setSelectedSessionId] = useState<string | null>(
        null,
    );

    const open = () => {
        // AI Playlist를 새로 열 때는 항상 새 대화 화면에서 시작한다.
        setSelectedSessionId(null);
        setView('chat');
        setIsOpen(true);
    };

    const close = () => {
        setIsOpen(false);
    };

    const openSessionList = () => {
        setView('list');
    };

    const returnToChat = () => {
        setView('chat');
    };

    const openSession = (sessionId: string) => {
        setSelectedSessionId(sessionId);
        setView('chat');
    };

    const startNewChat = () => {
        setSelectedSessionId(null);
        setView('chat');
    };

    return (
        <>
            {isOpen && (
                <section
                    className="
                        fixed bottom-24 right-6 z-50
                        flex h-[600px] w-[440px] max-w-[calc(100vw-3rem)] flex-col
                        overflow-hidden rounded-2xl border border-[#303038]
                        bg-[#18181d] shadow-xl
                    "
                    aria-label="AI Playlist"
                >
                    <header className="flex items-center justify-between border-b border-gray-800 px-4 py-3">
                        <div className="flex min-w-0 items-center gap-2">
                            {view === 'list' ? (
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    onClick={returnToChat}
                                    aria-label="채팅으로 돌아가기"
                                    className="shrink-0 text-gray-400 hover:bg-gray-700 hover:text-white"
                                >
                                    <ArrowLeft className="h-5 w-5" />
                                </Button>
                            ) : (
                                <Bot className="h-5 w-5 shrink-0 text-pink-500" />
                            )}

                            <div className="min-w-0">
                                <h2 className="font-semibold text-white">
                                    {view === 'list'
                                        ? '이전 대화'
                                        : 'AI Playlist'}
                                </h2>

                                <p className="truncate text-xs text-gray-400">
                                    {view === 'list'
                                        ? '이어서 대화할 채팅을 선택해보세요'
                                        : '원하는 콘텐츠를 말해보세요'}
                                </p>
                            </div>
                        </div>

                        {view === 'chat' ? (
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                onClick={openSessionList}
                                aria-label="이전 대화 목록"
                                className="text-gray-400 hover:bg-gray-700 hover:text-white"
                            >
                                <Menu className="h-5 w-5" />
                            </Button>
                        ) : (
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                onClick={startNewChat}
                                aria-label="새 대화"
                                className="text-gray-400 hover:bg-gray-700 hover:text-white"
                            >
                                <Plus className="h-5 w-5" />
                            </Button>
                        )}
                    </header>

                    {view === 'chat' ? (
                        <AiPlaylistChat
                            initialSessionId={selectedSessionId}
                        />
                    ) : (
                        <AiPlaylistSessionList onSelect={openSession} />
                    )}
                </section>
            )}

            <Button
                type="button"
                size="icon"
                className="
                    fixed bottom-6 right-6 z-50
                    h-14 w-14 rounded-full
                    bg-pink-500 text-white shadow-lg
                    hover:bg-pink-600 hover:text-white
                "
                onClick={() => {
                    if (isOpen) {
                        close();
                    } else {
                        open();
                    }
                }}
                aria-label={
                    isOpen ? 'AI Playlist 닫기' : 'AI Playlist 열기'
                }
                aria-expanded={isOpen}
            >
                {isOpen ? (
                    <X className="h-6 w-6 text-white" />
                ) : (
                    <Bot className="h-11 w-11 text-white" />
                )}
            </Button>
        </>
    );
}