import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import { useRoomList } from '../useRoomList';
import { CONNECTION_STATUS } from '../useServerConnection';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const roomsResponse = (rooms, metadata = {}) => ({ data: { data: rooms, metadata } });

const renderRoomList = ({ router = { push: vi.fn() } } = {}) =>
  renderHook(() =>
    useRoomList({
      currentUser: { token: 'token-1' },
      router,
      connectionStatus: CONNECTION_STATUS.CONNECTED,
      setConnectionStatus: vi.fn(),
      retryCount: 0,
      setRetryCount: vi.fn(),
      isRetrying: false,
      setIsRetrying: vi.fn(),
      getRetryDelay: vi.fn(() => 1000),
      attemptConnection: vi.fn(() => Promise.resolve(true)),
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces the list on refresh without leaving the refreshing flag on', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.refreshing).toBe(false);
    expect(axiosInstance.get).toHaveBeenCalledWith('/api/rooms', {
      params: { limit: 20 },
    });
  });

  it('loads the next cursor page and appends only rooms not already rendered', async () => {
    axiosInstance.get
      .mockResolvedValueOnce(roomsResponse(
        [{ _id: 'room-2' }, { _id: 'room-1' }],
        { hasMore: true, nextCursor: 'cursor-1' }
      ))
      .mockResolvedValueOnce(roomsResponse(
        [{ _id: 'room-1' }, { _id: 'room-0' }],
        { hasMore: false, nextCursor: null }
      ));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });
    expect(result.current.rooms).toHaveLength(2);
    expect(result.current.hasMore).toBe(true);

    await act(async () => {
      await result.current.loadMoreRooms();
    });

    expect(axiosInstance.get).toHaveBeenLastCalledWith('/api/rooms', {
      params: { limit: 20, cursor: 'cursor-1' },
    });
    expect(result.current.rooms.map((room) => room._id)).toEqual([
      'room-2',
      'room-1',
      'room-0',
    ]);
    expect(result.current.hasMore).toBe(false);
    expect(result.current.loadingMore).toBe(false);
  });

  it('refreshes back to the first 20-room page and resets its cursor', async () => {
    const firstPage = Array.from({ length: 20 }, (_, index) => ({ _id: `room-${index}` }));
    axiosInstance.get
      .mockResolvedValueOnce(roomsResponse(firstPage, { hasMore: true, nextCursor: 'cursor-1' }))
      .mockResolvedValueOnce(roomsResponse([{ _id: 'new-room' }], { hasMore: false }));

    const { result } = renderRoomList();
    await act(async () => result.current.fetchRooms());
    expect(result.current.rooms).toHaveLength(20);

    await act(async () => result.current.refreshRooms());

    expect(result.current.rooms).toEqual([{ _id: 'new-room' }]);
    expect(result.current.hasMore).toBe(false);
  });

  it('keeps the current list and stays quiet when a silent refresh fails', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    await act(async () => {
      await result.current.refreshRooms({ silent: true });
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a refresh failure when the user asked for it', async () => {
    axiosInstance.get.mockRejectedValue(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toMatchObject({
      title: '채팅방 목록 갱신 실패',
      showRetry: false,
    });
  });

  it('clears a previous error once a refresh succeeds', async () => {
    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).not.toBeNull();

    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });

  it('starts room navigation immediately after the join API succeeds', async () => {
    const router = { prefetch: vi.fn(), push: vi.fn(), replace: vi.fn() };
    axiosInstance.post.mockResolvedValue({ data: { success: true } });
    const { result } = renderRoomList({ router });

    await act(async () => {
      await result.current.handleJoinRoom('room-1');
    });

    expect(router.prefetch).toHaveBeenCalledWith('/chat/room-1');
    expect(router.push).toHaveBeenCalledWith('/chat/room-1');
  });
});
