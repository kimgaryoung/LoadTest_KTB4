import {
  deriveUniqueSortedMessages,
  insertUniqueChronologicalMessage,
} from '../messages/useMessageList';

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  setMessages,
  setHasMoreMessages,
  initialLoadCompletedRef,
}) => {
  if (!Array.isArray(loadedMessages)) {
    throw new Error('Invalid messages format');
  }

  const processedSnapshot = new Set(processedMessageIds.current);
  const nextProcessedMessageIds = new Set(processedSnapshot);
  loadedMessages.forEach(message => {
    if (message?._id) {
      nextProcessedMessageIds.add(message._id);
    }
  });
  processedMessageIds.current = nextProcessedMessageIds;

  let nextMessages;
  setMessages(prev => {
    nextMessages = deriveUniqueSortedMessages(prev, loadedMessages, processedSnapshot).messages;
    return nextMessages;
  });
  setHasMoreMessages(hasMore);

  if (isInitialLoad) {
    initialLoadCompletedRef.current = true;
  }

  return nextMessages;
};

export const applyReadReceipts = (messages, { userId, messageIds, timestamp }) => {
  const messageIdSet = new Set(messageIds);
  let changed = false;
  const nextMessages = messages.map(msg => {
    if (!messageIdSet.has(msg._id)) {
      return msg;
    }

    const alreadyRead = msg.readers?.some(reader =>
      reader.userId === userId || reader._id === userId
    );
    if (alreadyRead) {
      return msg;
    }

    changed = true;
    return {
      ...msg,
      readers: [...(msg.readers || []), { userId, readAt: timestamp || new Date() }],
    };
  });

  return changed ? nextMessages : messages;
};

export const appendIncomingMessage = (messages, incoming) => {
  return insertUniqueChronologicalMessage(messages, incoming);
};

const normalizeParticipants = (participants) => (
  Array.isArray(participants) ? participants : []
);

const isEventForAnotherRoom = (payload, roomId) => {
  const eventRoomId = payload?.roomId || payload?.room;
  return Boolean(eventRoomId && eventRoomId !== roomId);
};

const applyParticipantDelta = (participants, delta) => {
  const currentParticipants = normalizeParticipants(participants);

  if (!delta || typeof delta !== 'object' || !delta.participant) {
    return currentParticipants;
  }

  const participant = delta.participant;
  const participantId = participant.id || participant._id;
  if (!participantId) {
    return currentParticipants;
  }

  const participantKey = (item) => item?.id || item?._id;
  const existingIndex = currentParticipants.findIndex(item => participantKey(item) === participantId);

  if (delta.type === 'left') {
    return existingIndex === -1
      ? currentParticipants
      : currentParticipants.filter((_, index) => index !== existingIndex);
  }

  if (delta.type === 'joined' && existingIndex === -1) {
    return [...currentParticipants, participant];
  }

  return currentParticipants;
};

const normalizeParticipantUpdate = (payload) => {
  if (Array.isArray(payload)) {
    return { type: 'list', participants: payload };
  }

  if (!payload || typeof payload !== 'object') {
    return null;
  }

  if (Array.isArray(payload.participants)) {
    return { type: 'list', participants: payload.participants };
  }

  const nestedPayload = payload.data;
  if (Array.isArray(nestedPayload)) {
    return { type: 'list', participants: nestedPayload };
  }

  if (nestedPayload && typeof nestedPayload === 'object') {
    if (Array.isArray(nestedPayload.participants)) {
      return { type: 'list', participants: nestedPayload.participants };
    }

    if (nestedPayload.participant && nestedPayload.type) {
      return nestedPayload;
    }
  }

  if (
    (payload.type === 'joined' || payload.type === 'left') &&
    payload.participant
  ) {
    return payload;
  }

  return null;
};
export const createRoomEventHandlers = ({
  roomId,
  mountedRef,
  messageProcessingRef,
  processedMessageIds,
  initialLoadCompletedRef,
  processMessages,
  setRoom,
  setMessages,
  setLoadingMessages,
  setError,
  setHasMoreMessages,
  cleanup,
  logout,
  onReplace,
  handleReactionUpdate,
  showRejectedMessage,
}) => {
  const handlePreviousMessages = (response) => {
    if (!mountedRef.current || messageProcessingRef.current) return;
    try {
      messageProcessingRef.current = true;
      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format');
      }
      const { messages: loadedMessages = [], hasMore } = response;
      const isInitialLoad = !initialLoadCompletedRef.current;
      processMessages(loadedMessages, hasMore, isInitialLoad);
      setLoadingMessages(false);
    } catch (error) {
      setLoadingMessages(false);
      setError('메시지 처리 중 오류가 발생했습니다.');
      setHasMoreMessages(false);
    } finally {
      messageProcessingRef.current = false;
    }
  };

  return {
    onParticipantsUpdate: (payload) => {
      if (!mountedRef.current) return;
      const update = normalizeParticipantUpdate(payload);

      if (update?.roomId && update.roomId !== roomId) {
        return;
      }

      setRoom(prev => ({
        ...prev,
        participants: update?.type === 'list'
          ? update.participants
          : update
            ? applyParticipantDelta(prev?.participants, update)
            : normalizeParticipants(prev?.participants),
      }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current) return;
      if (isEventForAnotherRoom(payload, roomId)) return;
      setMessages(prev => applyReadReceipts(prev, payload));
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (isEventForAnotherRoom(incoming, roomId)) return;
      if (!incoming?._id || processedMessageIds.current.has(incoming._id)) return;
      processedMessageIds.current.add(incoming._id);
      setMessages(prev => appendIncomingMessage(prev, incoming));
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
      if (isEventForAnotherRoom(data, roomId)) return;
      handleReactionUpdate(data);
    },
    onSessionEnded: () => {
      if (!mountedRef.current) return;
      cleanup();
      logout();
      onReplace('/?error=session_expired');
    },
    onError: (error) => {
      if (!mountedRef.current) return;
      console.error('Socket error:', error);
      if (error?.code === 'MESSAGE_REJECTED') {
        showRejectedMessage(error.message || '금칙어가 포함되어 메시지를 전송할 수 없습니다.');
        return;
      }
      setError(error.message || '채팅 연결에 문제가 발생했습니다.');
    },
  };
};
