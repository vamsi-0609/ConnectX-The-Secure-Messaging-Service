import React, { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import { ChevronDown, Lock } from 'lucide-react';
import { MessageBubble } from './MessageBubble';
import { Message } from '../../types';
import { buildMessageGroups } from '../../utils/messageGroups';

interface MessageFeedProps {
  messages: Message[];
  currentUserId: number;
  showRawCiphertext: boolean;
  onDeleteMessage: (messageId: number, deleteForEveryone: boolean) => void;
}

export const MessageFeed: React.FC<MessageFeedProps> = ({
  messages,
  currentUserId,
  showRawCiphertext,
  onDeleteMessage,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [showScrollBottomBtn, setShowScrollBottomBtn] = useState(false);
  const prevMessageCountRef = useRef(0);
  const isNearBottomRef = useRef(true);

  const groups = useMemo(
    () => buildMessageGroups(messages, currentUserId),
    [messages, currentUserId]
  );

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const container = scrollContainerRef.current;
    if (!container) return;
    container.scrollTo({
      top: container.scrollHeight,
      behavior,
    });
  }, []);

  const handleScroll = () => {
    const container = scrollContainerRef.current;
    if (!container) return;
    const { scrollTop, scrollHeight, clientHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    isNearBottomRef.current = distanceFromBottom < 120;
    setShowScrollBottomBtn(distanceFromBottom > 150);
  };

  useEffect(() => {
    const isNewMessageAdded = messages.length > prevMessageCountRef.current;
    const isInitialLoad = prevMessageCountRef.current === 0 && messages.length > 0;

    if (isInitialLoad || (isNewMessageAdded && isNearBottomRef.current)) {
      scrollToBottom(isInitialLoad ? 'auto' : 'smooth');
    }

    prevMessageCountRef.current = messages.length;
  }, [messages, scrollToBottom]);

  return (
    <div className="h-full w-full relative min-h-0">
      <div
        ref={scrollContainerRef}
        onScroll={handleScroll}
        className="message-scroll h-full overflow-y-auto overflow-x-hidden overscroll-contain px-3 py-2 md:px-8 md:py-3 lg:px-12 xl:px-16"
      >
        {/* Mobile: unchanged narrow wrapper. Desktop: full chat width, top-aligned flow */}
        <div className="max-w-3xl mx-auto w-full pb-1 md:max-w-none md:mx-0 flex flex-col justify-start">
          {messages.length > 0 && (
            <div className="flex justify-center mb-3 md:mb-4">
              <div className="inline-flex items-center gap-1.5 px-2.5 py-0.5 md:px-3 md:py-1 rounded-full bg-slate-900/60 border border-indigo-500/15 text-[10px] md:text-[11px] text-indigo-200/70">
                <Lock className="w-3 h-3 text-pink-400" />
                <span>End-to-end encrypted</span>
              </div>
            </div>
          )}

          {messages.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center text-slate-500 space-y-2">
              <div className="w-12 h-12 rounded-full bg-indigo-600/10 border border-indigo-500/20 flex items-center justify-center">
                <Lock className="w-5 h-5 text-indigo-400" />
              </div>
              <p className="text-sm font-medium text-slate-400">No messages yet</p>
              <p className="text-xs text-slate-500">Send a message to start this encrypted conversation</p>
            </div>
          ) : (
            <div className="flex flex-col">
              {groups.map((item) => {
                if (item.type === 'date') {
                  return (
                    <div key={item.key} className="flex justify-center my-2 md:my-3">
                      <span className="px-2.5 py-0.5 md:px-3 md:py-1 rounded-md bg-slate-900/70 border border-slate-700/40 text-[10px] md:text-[11px] font-medium text-slate-400 uppercase tracking-wide">
                        {item.label}
                      </span>
                    </div>
                  );
                }

                return (
                  <MessageBubble
                    key={item.key}
                    message={item.message}
                    isSelf={item.isSelf}
                    isGroupedWithPrev={item.isGroupedWithPrev}
                    isGroupedWithNext={item.isGroupedWithNext}
                    showRawCiphertext={showRawCiphertext}
                    onDeleteMessage={onDeleteMessage}
                  />
                );
              })}
            </div>
          )}
        </div>
      </div>

      {showScrollBottomBtn && (
        <button
          onClick={() => scrollToBottom('smooth')}
          className="absolute bottom-3 right-4 md:bottom-4 md:right-8 z-10 p-2 md:p-2.5 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg border border-indigo-400/30 transition-all"
          title="Scroll to latest messages"
          aria-label="Scroll to bottom"
        >
          <ChevronDown className="w-4 h-4 md:w-5 md:h-5" />
        </button>
      )}
    </div>
  );
};
