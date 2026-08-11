import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  post: vi.fn(),
  withAuth: vi.fn((Component) => Component),
}));

vi.mock('next/router', () => ({
  useRouter: () => ({
    push: mocks.push,
  }),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'user-1',
      token: 'token-1',
      sessionId: 'session-1',
    },
  }),
  withAuth: mocks.withAuth,
}));

vi.mock('@/lib/api/client', () => ({
  default: {
    post: mocks.post,
  },
}));

import AuthenticatedNewChatRoom, { NewChatRoom } from '../../pages/chat/new';

describe('NewChatRoom', () => {
  beforeEach(() => {
    mocks.push.mockReset();
    mocks.post.mockReset();
  });

  it('is exported through the authentication boundary', () => {
    expect(mocks.withAuth).toHaveBeenCalledWith(NewChatRoom);
    expect(AuthenticatedNewChatRoom).toBe(NewChatRoom);
  });

  it('enables room creation after the controlled room name changes', () => {
    render(<NewChatRoom />);

    const createButton = screen.getByTestId('create-chat-room-button');
    expect(createButton).toBeDisabled();

    fireEvent.change(screen.getByTestId('chat-room-name-input'), {
      target: { value: '5인 테스트 채팅방' },
    });

    expect(createButton).toBeEnabled();
  });

  it('creates and joins a room exactly once before navigating', async () => {
    mocks.post
      .mockResolvedValueOnce({ data: { data: { _id: 'room-1' } } })
      .mockResolvedValueOnce({ data: { success: true } });
    mocks.push.mockResolvedValue(true);

    render(<NewChatRoom />);

    fireEvent.change(screen.getByTestId('chat-room-name-input'), {
      target: { value: '  5인 테스트 채팅방  ' },
    });
    fireEvent.click(screen.getByTestId('create-chat-room-button'));

    await waitFor(() => {
      expect(mocks.post).toHaveBeenCalledTimes(2);
    });

    expect(mocks.post).toHaveBeenNthCalledWith(1, '/api/rooms', {
      name: '5인 테스트 채팅방',
      password: undefined,
    });
    expect(mocks.post).toHaveBeenNthCalledWith(2, '/api/rooms/room-1/join', {
      password: undefined,
    });
    expect(mocks.push).toHaveBeenCalledTimes(1);
    expect(mocks.push).toHaveBeenCalledWith('/chat/room-1');
  });
});
