import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import socketClient from '@/lib/socket/socketClient';
import { useRoomsSocket } from '../useRoomsSocket';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    connect: vi.fn(),
  },
}));

const currentUser = {
  token: 'token-1',
  sessionId: 'session-1',
};

const renderRoomsSocket = (socket, overrides = {}) => {
  socketClient.connect.mockResolvedValue(socket);

  return renderHook(() =>
    useRoomsSocket({
      currentUser,
      router: { push: vi.fn() },
      setConnectionStatus: vi.fn(),
      setRooms: vi.fn(),
      ...overrides,
    })
  );
};

const createSocket = () => ({
  on: vi.fn(),
  emit: vi.fn(),
  disconnect: vi.fn(),
});

describe('useRoomsSocket', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not emit joinRoomList because the server joins room-list on connect', async () => {
    const socket = {
      on: vi.fn(),
      emit: vi.fn(),
      disconnect: vi.fn(),
    };

    renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('connect', expect.any(Function));
    });

    const connectHandler = socket.on.mock.calls.find(([event]) => event === 'connect')[1];
    connectHandler();

    expect(socket.emit).not.toHaveBeenCalledWith('joinRoomList');
  });

  it('does not register roomDeleted without a server-side room delete event', async () => {
    const socket = {
      on: vi.fn(),
      emit: vi.fn(),
      disconnect: vi.fn(),
    };

    renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalled();
    });

    const registeredEvents = socket.on.mock.calls.map(([event]) => event);
    expect(registeredEvents).not.toContain('roomDeleted');
  });

  it('does not register the removed roomActivity event', async () => {
    const socket = createSocket();
    renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalled();
    });

    const registeredEvents = socket.on.mock.calls.map(([event]) => event);
    expect(registeredEvents).not.toContain('roomActivity');
  });
});
