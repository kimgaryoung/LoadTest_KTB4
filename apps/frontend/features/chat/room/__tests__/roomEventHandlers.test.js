import { describe, expect, it, vi } from 'vitest';
import {
  appendIncomingMessage,
  appendIncomingNormalizedMessage,
  applyReadReceipts,
  applyReadReceiptsToNormalizedMessages,
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from '../roomEventHandlers';
import { normalizeMessages, selectMessagesArray } from '../../messages/normalizedMessages';

describe('roomEventHandlers', () => {
  it('processes loaded messages through the shared message list reducer', () => {
    const processedMessageIds = { current: new Set(['message-1']) };
    const initialLoadCompletedRef = { current: false };
    const normalizedMessages = normalizeMessages([
      { _id: 'message-1', timestamp: '2026-07-07T00:00:02.000Z' },
    ]);
    const setMessages = vi.fn(updater => {
      const currentMessages = [{ _id: 'message-1', timestamp: '2026-07-07T00:00:02.000Z' }];
      return updater(currentMessages);
    });
    const setNormalizedMessages = vi.fn();
    const setHasMoreMessages = vi.fn();

    const result = processLoadedRoomMessages({
      loadedMessages: [
        { _id: 'message-1', timestamp: '2026-07-07T00:00:02.000Z' },
        { _id: 'message-2', timestamp: '2026-07-07T00:00:01.000Z' },
      ],
      hasMore: false,
      isInitialLoad: true,
      processedMessageIds,
      normalizedMessages,
      setMessages,
      setNormalizedMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    });

    expect(result.map(message => message._id)).toEqual(['message-2', 'message-1']);
    expect(selectMessagesArray(setNormalizedMessages.mock.calls[0][0]).map(message => message._id)).toEqual([
      'message-2',
      'message-1',
    ]);
    expect(processedMessageIds.current.has('message-2')).toBe(true);
    expect(setHasMoreMessages).toHaveBeenCalledWith(false);
    expect(initialLoadCompletedRef.current).toBe(true);
  });

  it('applies read receipts without duplicating existing readers', () => {
    const messages = [
      {
        _id: 'message-1',
        readers: [{ userId: 'user-2', readAt: 'existing' }],
      },
      {
        _id: 'message-2',
        readers: [],
      },
    ];

    expect(
      applyReadReceipts(messages, {
        userId: 'user-2',
        messageIds: ['message-1', 'message-2'],
        timestamp: '2026-07-07T00:00:00.000Z',
      })
    ).toEqual([
      {
        _id: 'message-1',
        readers: [{ userId: 'user-2', readAt: 'existing' }],
      },
      {
        _id: 'message-2',
        readers: [{ userId: 'user-2', readAt: '2026-07-07T00:00:00.000Z' }],
      },
    ]);
  });

  it('keeps the existing array when a read receipt changes nothing', () => {
    const messages = [
      {
        _id: 'message-1',
        readers: [{ userId: 'user-2', readAt: 'existing' }],
      },
    ];

    expect(
      applyReadReceipts(messages, {
        userId: 'user-2',
        messageIds: ['message-1'],
        timestamp: '2026-07-07T00:00:00.000Z',
      })
    ).toBe(messages);
  });

  it('applies read receipts to normalized messages by id', () => {
    const normalizedMessages = normalizeMessages([
      {
        _id: 'message-1',
        readers: [{ userId: 'user-2', readAt: 'existing' }],
      },
      {
        _id: 'message-2',
        readers: [],
      },
      {
        _id: 'message-3',
        readers: [],
      },
    ]);

    const updated = applyReadReceiptsToNormalizedMessages(normalizedMessages, {
      userId: 'user-2',
      messageIds: ['message-1', 'message-2'],
      timestamp: '2026-07-07T00:00:00.000Z',
    });

    expect(updated.ids).toBe(normalizedMessages.ids);
    expect(updated.byId['message-1']).toBe(normalizedMessages.byId['message-1']);
    expect(updated.byId['message-2'].readers).toEqual([
      { userId: 'user-2', readAt: '2026-07-07T00:00:00.000Z' },
    ]);
    expect(updated.byId['message-3']).toBe(normalizedMessages.byId['message-3']);
  });

  it('appends incoming messages only once', () => {
    const currentMessages = [{ _id: 'message-1' }];

    expect(appendIncomingMessage(currentMessages, { _id: 'message-1' })).toBe(
      currentMessages
    );
    expect(
      appendIncomingMessage(currentMessages, { _id: 'message-2' })
    ).toEqual([{ _id: 'message-1' }, { _id: 'message-2' }]);
  });

  it('keeps delayed incoming messages in chronological order', () => {
    const currentMessages = [
      { _id: 'message-1', timestamp: 1000 },
      { _id: 'message-3', timestamp: 3000 },
    ];

    expect(
      appendIncomingMessage(currentMessages, { _id: 'message-2', timestamp: 2000 })
        .map(message => message._id)
    ).toEqual(['message-1', 'message-2', 'message-3']);
  });

  it('appends incoming normalized messages in chronological order', () => {
    const currentMessages = normalizeMessages([
      { _id: 'message-1', timestamp: 1000 },
      { _id: 'message-3', timestamp: 3000 },
    ]);

    expect(
      appendIncomingNormalizedMessage(currentMessages, { _id: 'message-2', timestamp: 2000 }).ids
    ).toEqual(['message-1', 'message-2', 'message-3']);
  });

  it('keeps live messages when the updater is invoked twice (StrictMode)', () => {
    const mountedRef = { current: true };
    const processedMessageIds = { current: new Set() };
    let committed = [];
    const setMessages = vi.fn(updater => {
      // React StrictMode invokes state updaters twice with the same base state
      // in development to surface impure updaters. Both calls must agree.
      const first = updater(committed);
      const second = updater(committed);
      expect(second).toEqual(first);
      committed = second;
    });
    let committedNormalized = normalizeMessages([]);
    const setNormalizedMessages = vi.fn(updater => {
      const first = updater(committedNormalized);
      const second = updater(committedNormalized);
      expect(second).toEqual(first);
      committedNormalized = second;
    });

    const handlers = createRoomEventHandlers({
      mountedRef,
      messageProcessingRef: { current: false },
      processedMessageIds,
      initialLoadCompletedRef: { current: true },
      processMessages: vi.fn(),
      setRoom: vi.fn(),
      setMessages,
      setNormalizedMessages,
      setLoadingMessages: vi.fn(),
      setError: vi.fn(),
      setHasMoreMessages: vi.fn(),
      cleanup: vi.fn(),
      logout: vi.fn(),
      onReplace: vi.fn(),
      handleReactionUpdate: vi.fn(),
      showRejectedMessage: vi.fn(),
    });

    handlers.onMessage({ _id: 'message-live' });

    expect(setMessages).not.toHaveBeenCalled();
    expect(committed).toEqual([]);
    expect(committedNormalized.ids).toEqual(['message-live']);
  });

  it('creates room event handlers with mounted and processing guards', () => {
    const mountedRef = { current: true };
    const messageProcessingRef = { current: false };
    const processedMessageIds = { current: new Set() };
    const initialLoadCompletedRef = { current: false };
    const setRoom = vi.fn();
    const setMessages = vi.fn();
    const setNormalizedMessages = vi.fn();
    const setLoadingMessages = vi.fn();
    const setError = vi.fn();
    const setHasMoreMessages = vi.fn();
    const processMessages = vi.fn();
    const cleanup = vi.fn();
    const logout = vi.fn();
    const onReplace = vi.fn();
    const handleReactionUpdate = vi.fn();
    const showRejectedMessage = vi.fn();

    const handlers = createRoomEventHandlers({
      mountedRef,
      messageProcessingRef,
      processedMessageIds,
      initialLoadCompletedRef,
      processMessages,
      setRoom,
      setMessages,
      setNormalizedMessages,
      setLoadingMessages,
      setError,
      setHasMoreMessages,
      cleanup,
      logout,
      onReplace,
      handleReactionUpdate,
      showRejectedMessage,
    });

    handlers.onParticipantsUpdate([{ _id: 'user-1' }]);
    handlers.onMessagesRead({
      userId: 'user-1',
      messageIds: ['message-1'],
      timestamp: '2026-07-07T00:00:00.000Z',
    });
    handlers.onMessage({ _id: 'message-1' });
    handlers.onPreviousMessagesLoaded({ messages: [{ _id: 'message-2' }], hasMore: true });
    handlers.onMessageReactionUpdate({ messageId: 'message-1' });
    handlers.onSessionEnded();
    handlers.onError({ code: 'MESSAGE_REJECTED', message: 'blocked' });

    expect(setRoom).toHaveBeenCalledWith(expect.any(Function));
    expect(setMessages).not.toHaveBeenCalled();
    expect(setNormalizedMessages).toHaveBeenCalledTimes(2);
    expect(processMessages).toHaveBeenCalledWith([{ _id: 'message-2' }], true, true);
    expect(setLoadingMessages).toHaveBeenCalledWith(false);
    expect(handleReactionUpdate).toHaveBeenCalledWith({ messageId: 'message-1' });
    expect(cleanup).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledTimes(1);
    expect(onReplace).toHaveBeenCalledWith('/?error=session_expired');
    expect(showRejectedMessage).toHaveBeenCalledWith('blocked');
  });
});
