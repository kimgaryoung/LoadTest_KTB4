import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatMessages from '../ChatMessages';

vi.mock('../../hooks/useInfiniteScroll', () => ({
  useInfiniteScroll: () => ({ sentinelRef: { current: null } }),
}));

vi.mock('../../hooks/useAutoScroll', () => ({
  useAutoScroll: () => ({
    containerRef: { current: null },
    scrollToBottom: vi.fn(),
    isNearBottom: true,
  }),
}));

vi.mock('../SystemMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../FileMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../UserMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

describe('ChatMessages', () => {
  it('renders messages in state order without sorting or mutating the input array', () => {
    const messages = [
      {
        _id: 'late',
        content: 'late message',
        timestamp: '2026-06-20T12:00:00.000Z',
        sender: { _id: 'other' },
      },
      {
        _id: 'early',
        content: 'early message',
        timestamp: '2026-06-20T11:00:00.000Z',
        sender: { _id: 'other' },
      },
    ];
    const originalOrder = messages.map((message) => message._id);

    render(
      React.createElement(ChatMessages, {
        messages,
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    expect(screen.getAllByTestId('message').map((node) => node.textContent)).toEqual([
      'late message',
      'early message',
    ]);
    expect(messages.map((message) => message._id)).toEqual(originalOrder);
  });

  it('renders messages from messageIds and messagesById without requiring an array lookup', () => {
    render(
      React.createElement(ChatMessages, {
        messages: [],
        messageIds: ['message-2', 'message-1'],
        messagesById: {
          'message-1': {
            _id: 'message-1',
            content: 'first by id',
            sender: { _id: 'other' },
          },
          'message-2': {
            _id: 'message-2',
            content: 'second by id',
            sender: { _id: 'other' },
          },
        },
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    expect(screen.getAllByTestId('message').map((node) => node.textContent)).toEqual([
      'second by id',
      'first by id',
    ]);
  });

  it('keeps optimized message wrappers discoverable in the rendered DOM', () => {
    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'discoverable message',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: true,
        loadingMessages: true,
      })
    );

    const message = screen.getByText('discoverable message');
    const optimizedWrapper = message.closest('[style]');

    expect(message).toBeInTheDocument();
    expect(optimizedWrapper).toHaveStyle({
      contentVisibility: 'auto',
      containIntrinsicSize: '1px 96px',
    });
    expect(screen.getByText('이전 메시지를 불러오는 중...')).toBeInTheDocument();
  });

  it('marks visible unread messages as read in one batch', () => {
    const onVisibleMessagesRead = vi.fn();

    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'already read',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
            readers: [{ userId: 'me' }],
          },
          {
            _id: 'message-2',
            content: 'unread',
            timestamp: '2026-06-20T11:01:00.000Z',
            sender: { _id: 'other' },
            readers: [],
          },
          {
            _id: 'message-3',
            content: 'mine',
            timestamp: '2026-06-20T11:02:00.000Z',
            sender: { _id: 'me' },
            readers: [],
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: false,
        onVisibleMessagesRead,
      })
    );

    expect(onVisibleMessagesRead).toHaveBeenCalledWith(['message-2']);
  });

  it('loads more messages from the virtual range when the first row is visible', () => {
    const onLoadMore = vi.fn();

    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'first visible',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: true,
        loadingMessages: false,
        onLoadMore,
      })
    );

    expect(onLoadMore).toHaveBeenCalledTimes(1);
  });
});
