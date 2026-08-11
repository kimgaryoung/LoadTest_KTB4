import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Spinner, Text } from '@vapor-ui/core';
import { useVirtualizer } from '@tanstack/react-virtual';
import SystemMessage from './SystemMessage';
import FileMessage from './FileMessage';
import UserMessage from './UserMessage';
import { useInfiniteScroll } from '../hooks/useInfiniteScroll';

const LoadingIndicator = React.memo(() => (
  <div className="loading-messages">
    <Spinner size="md" colorPalette="primary" aria-label="이전 메시지 로딩 중" />
    <span className="text-secondary text-sm">이전 메시지를 불러오는 중...</span>
  </div>
));
LoadingIndicator.displayName = 'LoadingIndicator';

const MessageHistoryEnd = React.memo(() => (
  <div className="text-center p-2 mb-4" data-testid="message-history-end">
    <Text typography="body2" foreground="hint-100">더 이상 불러올 메시지가 없습니다.</Text>
  </div>
));
MessageHistoryEnd.displayName = 'MessageHistoryEnd';

const EmptyMessages = React.memo(() => (
  <div className="empty-messages">
    <Text typography="body1">아직 메시지가 없습니다.</Text>
    <Text typography="body2" foreground="hint-100">첫 메시지를 보내보세요!</Text>
  </div>
));
EmptyMessages.displayName = 'EmptyMessages';

const ChatMessages = ({
  messages = [],
  currentUser = null,
  room = null,
  loadingMessages = false,
  hasMoreMessages = true,
  onReactionAdd = () => {},
  onReactionRemove = () => {},
  onLoadMore = () => {}
}) => {
  const currentUserId = currentUser?.id;

  // 무한 스크롤 훅
  const { sentinelRef } = useInfiniteScroll(
    onLoadMore,
    hasMoreMessages,
    loadingMessages
  );

  const containerRef = useRef(null);
  const [scrollElement, setScrollElement] = useState(null);
  const initialScrollMessageKeyRef = useRef(null);
  const isNearBottomRef = useRef(true);
  const previousLatestMessageKeyRef = useRef(null);
  const previousLoadingMessagesRef = useRef(false);
  const previousLoadAnchorRef = useRef(null);

  const setContainerRef = useCallback((node) => {
    containerRef.current = node;
    setScrollElement(node);
  }, []);
  const isMine = useCallback((msg) => {
    if (!msg?.sender || !currentUserId) return false;
    
    return (
      msg.sender._id === currentUserId || 
      msg.sender.id === currentUserId ||
      msg.sender === currentUserId
    );
  }, [currentUserId]);

  const allMessages = useMemo(() => {
    if (!Array.isArray(messages)) return [];

    return [...messages].sort((a, b) => {
      if (!a?.timestamp || !b?.timestamp) return 0;
      return new Date(a.timestamp) - new Date(b.timestamp);
    });
  }, [messages]);

  // TanStack Virtual returns imperative helpers that React Compiler cannot memoize safely.
  // eslint-disable-next-line react-hooks/incompatible-library
  const rowVirtualizer = useVirtualizer({
    count: allMessages.length,
    getScrollElement: () => scrollElement,
    getItemKey: (index) => allMessages[index]?._id || `msg-${index}`,
    estimateSize: () => 128,
    overscan: 8,
    initialRect: {
      width: 0,
      height: 600,
    },
  });
  const measuredVirtualRows = rowVirtualizer.getVirtualItems();
  const virtualRows = measuredVirtualRows.length > 0
    ? measuredVirtualRows
    : allMessages.slice(0, 14).map((message, index) => ({
        index,
        key: message?._id || `msg-${index}`,
        start: index * 128,
      }));
  const totalVirtualSize = rowVirtualizer.getTotalSize() || allMessages.length * 128;
  const latestMessage = allMessages[allMessages.length - 1];
  const latestMessageKey = latestMessage?._id || latestMessage?.timestamp;

  const getIsNearBottom = useCallback(() => {
    if (!scrollElement) return true;

    const distanceFromBottom = scrollElement.scrollHeight
      - (scrollElement.scrollTop + scrollElement.clientHeight);

    return distanceFromBottom <= 100;
  }, [scrollElement]);

  useEffect(() => {
    if (!scrollElement) return undefined;

    const handleScroll = () => {
      isNearBottomRef.current = getIsNearBottom();
    };

    handleScroll();
    scrollElement.addEventListener('scroll', handleScroll, { passive: true });

    return () => {
      scrollElement.removeEventListener('scroll', handleScroll);
    };
  }, [getIsNearBottom, scrollElement]);

  const roomKey = room?._id || room?.id || 'default-room';

  useEffect(() => {
    initialScrollMessageKeyRef.current = null;
    previousLatestMessageKeyRef.current = null;
    previousLoadingMessagesRef.current = false;
    previousLoadAnchorRef.current = null;
  }, [roomKey]);

  useLayoutEffect(() => {
    if (!scrollElement || allMessages.length === 0 || loadingMessages) {
      return undefined;
    }

    if (!latestMessageKey || initialScrollMessageKeyRef.current) {
      return undefined;
    }

    let cancelled = false;
    const frameIds = [];
    const scrollToLatestMessage = () => {
      if (cancelled) return;

      rowVirtualizer.scrollToIndex(allMessages.length - 1, {
        align: 'end',
        behavior: 'auto',
      });
      scrollElement.scrollTop = scrollElement.scrollHeight;
      initialScrollMessageKeyRef.current = latestMessageKey;
      previousLatestMessageKeyRef.current = latestMessageKey;
    };

    frameIds.push(requestAnimationFrame(() => {
      scrollToLatestMessage();

      frameIds.push(requestAnimationFrame(() => {
        scrollToLatestMessage();
      }));
    }));

    return () => {
      cancelled = true;
      frameIds.forEach((frameId) => {
        cancelAnimationFrame(frameId);
      });
    };
  }, [allMessages.length, latestMessageKey, loadingMessages, rowVirtualizer, scrollElement]);

  useLayoutEffect(() => {
    if (!scrollElement || allMessages.length === 0) {
      previousLoadingMessagesRef.current = loadingMessages;
      return undefined;
    }

    if (loadingMessages && !previousLoadingMessagesRef.current) {
      const firstVisibleRow = rowVirtualizer.getVirtualItems()[0];
      const firstVisibleMessage = allMessages[firstVisibleRow?.index ?? 0];

      previousLoadAnchorRef.current = firstVisibleMessage
        ? {
            key: firstVisibleMessage._id || firstVisibleMessage.timestamp,
            scrollHeight: scrollElement.scrollHeight,
            scrollTop: scrollElement.scrollTop,
            offset: firstVisibleRow
              ? firstVisibleRow.start - scrollElement.scrollTop
              : 0,
          }
        : null;
    }

    if (!loadingMessages && previousLoadingMessagesRef.current) {
      const anchor = previousLoadAnchorRef.current;
      previousLoadAnchorRef.current = null;

      if (anchor?.key) {
        const heightDifference = scrollElement.scrollHeight - anchor.scrollHeight;

        if (heightDifference > 0) {
          scrollElement.scrollTop = anchor.scrollTop + heightDifference;
        }

        const anchorIndex = allMessages.findIndex((message) => (
          (message._id || message.timestamp) === anchor.key
        ));

        if (anchorIndex >= 0) {
          rowVirtualizer.scrollToIndex(anchorIndex, {
            align: 'start',
            behavior: 'auto',
          });

          const anchorRow = rowVirtualizer
            .getVirtualItems()
            .find((virtualRow) => virtualRow.index === anchorIndex);

          if (anchorRow) {
            scrollElement.scrollTop = anchorRow.start - anchor.offset;
          }

          previousLoadingMessagesRef.current = loadingMessages;
          return undefined;
        }
      }
    }

    previousLoadingMessagesRef.current = loadingMessages;
    return undefined;
  }, [allMessages, loadingMessages, rowVirtualizer, scrollElement]);

  useLayoutEffect(() => {
    if (!scrollElement || !latestMessageKey || loadingMessages) {
      return undefined;
    }

    if (!previousLatestMessageKeyRef.current) {
      previousLatestMessageKeyRef.current = latestMessageKey;
      return undefined;
    }

    if (previousLatestMessageKeyRef.current === latestMessageKey) {
      return undefined;
    }

    const senderId = latestMessage?.sender?._id
      || latestMessage?.sender?.id
      || latestMessage?.sender;
    const isMyMessage = senderId === currentUserId;
    previousLatestMessageKeyRef.current = latestMessageKey;

    if (!isMyMessage && !isNearBottomRef.current) {
      return undefined;
    }

    const frameId = requestAnimationFrame(() => {
      rowVirtualizer.scrollToIndex(allMessages.length - 1, {
        align: 'end',
        behavior: 'auto',
      });
    });

    return () => cancelAnimationFrame(frameId);
  }, [
    allMessages.length,
    currentUserId,
    latestMessage,
    latestMessageKey,
    loadingMessages,
    rowVirtualizer,
    scrollElement,
  ]);

  const renderMessage = useCallback((msg, idx) => {
    if (!msg) return null;

    const commonProps = {
      currentUser,
      room,
      onReactionAdd,
      onReactionRemove
    };

    const MessageComponent = {
      system: SystemMessage,
      file: FileMessage
    }[msg.type] || UserMessage;

    return (
      <div
        key={msg._id || `msg-${idx}`}
        style={{
          contentVisibility: 'auto',
          containIntrinsicSize: '1px 96px',
        }}
      >
        <MessageComponent
          {...commonProps}
          msg={msg}
          content={msg.content}
          isMine={msg.type !== 'system' ? isMine(msg) : undefined}
          isStreaming={msg.type === 'ai' ? (msg.isStreaming || false) : undefined}
        />
      </div>
    );
  }, [currentUser, room, isMine, onReactionAdd, onReactionRemove]);

  return (
    <div
      ref={setContainerRef}
      className="h-full overflow-y-auto overflow-x-hidden [overflow-scrolling:touch]"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--vapor-size-space-200)',
        padding: 'var(--vapor-size-space-300)',
      }}
      role="log"
      aria-live="polite"
      aria-atomic="false"
      data-testid="chat-messages-container"
    >
      {/* Sentinel 요소 - 스크롤 맨 위에 배치하여 위로 스크롤 시 이전 메시지 로드 */}
      {hasMoreMessages && (
        <div
          ref={sentinelRef}
          style={{
            height: '20px',
            margin: '10px 0',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center'
          }}
        >
          {loadingMessages && <LoadingIndicator />}
        </div>
      )}

      {!hasMoreMessages && messages.length > 0 && (
        <MessageHistoryEnd />
      )}

      {allMessages.length === 0 ? (
        <EmptyMessages />
      ) : (
        <div
          style={{
            height: `${totalVirtualSize}px`,
            position: 'relative',
            width: '100%',
          }}
        >
          {virtualRows.map((virtualRow) => {
            const msg = allMessages[virtualRow.index];

            return (
              <div
                key={virtualRow.key}
                ref={rowVirtualizer.measureElement}
                data-index={virtualRow.index}
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  transform: `translateY(${virtualRow.start}px)`,
                }}
              >
                {renderMessage(msg, virtualRow.index)}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

ChatMessages.displayName = 'ChatMessages';

export default React.memo(ChatMessages);
