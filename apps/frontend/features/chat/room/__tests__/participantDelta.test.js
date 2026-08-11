import { describe, expect, it, vi } from 'vitest';
import { createRoomEventHandlers } from '../roomEventHandlers';

const createParticipantHandlers = (roomId, participants) => {
  let room = { participants };
  const setRoom = vi.fn((updater) => {
    room = updater(room);
  });

  const handlers = createRoomEventHandlers({
    roomId,
    mountedRef: { current: true },
    messageProcessingRef: { current: false },
    processedMessageIds: { current: new Set() },
    initialLoadCompletedRef: { current: true },
    processMessages: vi.fn(),
    setRoom,
    setMessages: vi.fn(),
    setNormalizedMessages: vi.fn(),
    setLoadingMessages: vi.fn(),
    setError: vi.fn(),
    setHasMoreMessages: vi.fn(),
    cleanup: vi.fn(),
    logout: vi.fn(),
    onReplace: vi.fn(),
    handleReactionUpdate: vi.fn(),
    showRejectedMessage: vi.fn(),
  });

  return { handlers, getRoom: () => room, setRoom };
};

describe('participant delta handling', () => {
  it('ignores a delta from a room other than the displayed room', () => {
    const { handlers, getRoom, setRoom } = createParticipantHandlers('room-current', [
      { id: 'user-1', name: 'Current user' },
    ]);

    handlers.onParticipantsUpdate({
      roomId: 'room-other',
      type: 'joined',
      participant: { id: 'user-2', name: 'Other room user' },
    });

    expect(setRoom).not.toHaveBeenCalled();
    expect(getRoom().participants).toEqual([{ id: 'user-1', name: 'Current user' }]);
  });

  it('applies joined and left deltas for the displayed room', () => {
    const { handlers, getRoom } = createParticipantHandlers('room-current', [
      { id: 'user-1', name: 'Current user' },
    ]);

    handlers.onParticipantsUpdate({
      roomId: 'room-current',
      type: 'joined',
      participant: { id: 'user-2', name: 'Joining user' },
    });
    handlers.onParticipantsUpdate({
      roomId: 'room-current',
      type: 'left',
      participant: { id: 'user-1', name: 'Current user' },
    });

    expect(getRoom().participants).toEqual([
      { id: 'user-2', name: 'Joining user' },
    ]);
  });
});
