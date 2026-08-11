import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { useReactionHandling } from '../useReactionHandling';
import { normalizeMessages } from '../../messages/normalizedMessages';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    sendMessageReaction: vi.fn(),
  },
}));

vi.mock('@/components/Toast', () => ({
  Toast: { error: vi.fn() },
}));

const currentUser = { id: 'user-1' };
const messages = [{ _id: 'message-1', reactions: { '👍': ['user-1'] } }];
const normalizedMessages = normalizeMessages(messages);

describe('useReactionHandling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    socketClient.canSend.mockReturnValue(true);
  });

  it('delegates reaction add to socketClient', async () => {
    const setNormalizedMessages = vi.fn();
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, normalizedMessages, setNormalizedMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).toHaveBeenCalledWith(
      'message-1',
      '👍',
      'add',
    );
  });

  it('delegates reaction remove to socketClient', async () => {
    const setNormalizedMessages = vi.fn();
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, normalizedMessages, setNormalizedMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).toHaveBeenCalledWith(
      'message-1',
      '👍',
      'remove',
    );
  });

  it('does not send reaction add when the socket client cannot send', async () => {
    const setNormalizedMessages = vi.fn((updater) => updater(normalizedMessages));
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, normalizedMessages, setNormalizedMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('리액션 추가에 실패했습니다.');
  });

  it('does not send reaction remove when the socket client cannot send', async () => {
    const setNormalizedMessages = vi.fn((updater) => updater(normalizedMessages));
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, normalizedMessages, setNormalizedMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('리액션 제거에 실패했습니다.');
  });

  it('updates only the target message by id', async () => {
    let state = normalizeMessages([
      { _id: 'message-1', reactions: {} },
      { _id: 'message-2', reactions: { '👍': ['user-2'] } },
      { _id: 'message-3', reactions: {} },
    ]);
    const setNormalizedMessages = vi.fn((updater) => {
      state = updater(state);
    });
    const { result } = renderHook(() =>
      useReactionHandling({
        currentUser,
        normalizedMessages: state,
        setNormalizedMessages,
      })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-2', '👍');
    });

    expect(state.byId['message-1'].reactions).toEqual({});
    expect(state.byId['message-2'].reactions['👍']).toEqual(['user-2', 'user-1']);
    expect(state.byId['message-3'].reactions).toEqual({});
    expect(state.ids).toEqual(['message-1', 'message-2', 'message-3']);
  });

  it('ignores missing reaction update targets safely', async () => {
    let state = normalizeMessages([
      { _id: 'message-1', reactions: {} },
      { _id: 'message-2', reactions: {} },
    ]);
    const setNormalizedMessages = vi.fn((updater) => {
      state = updater(state);
    });
    const { result } = renderHook(() =>
      useReactionHandling({
        currentUser,
        normalizedMessages: state,
        setNormalizedMessages,
      })
    );

    await act(async () => {
      await result.current.handleReactionUpdate({
        messageId: 'missing',
        reactions: { '😀': ['user-1'] },
      });
    });

    expect(state.byId['message-1'].reactions).toEqual({});
    expect(state.byId['message-2'].reactions).toEqual({});
  });
});
