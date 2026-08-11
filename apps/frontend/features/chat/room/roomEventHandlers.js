import {
  insertUniqueChronologicalMessage,
} from '../messages/useMessageList';
import {
  insertNormalizedMessage,
  mergeNormalizedMessages,
  normalizeMessages,
  selectMessagesArray,
  updateNormalizedMessageById,
} from '../messages/normalizedMessages';

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  normalizedMessages,
  setMessages,
  setNormalizedMessages,
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

  const currentNormalizedMessages = normalizedMessages || normalizeMessages([]);
  const result = mergeNormalizedMessages(
    currentNormalizedMessages,
    loadedMessages,
    processedSnapshot
  );
  const nextMessages = selectMessagesArray(result.messages);

  if (setNormalizedMessages) {
    setNormalizedMessages(result.messages);
  } else {
    setMessages(nextMessages);
  }
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

export const applyReadReceiptsToNormalizedMessages = (
  normalizedMessages,
  { userId, messageIds, timestamp }
) => {
  let nextMessages = normalizedMessages;

  messageIds.forEach((messageId) => {
    nextMessages = updateNormalizedMessageById(nextMessages, messageId, (message) => {
      const alreadyRead = message.readers?.some(reader =>
        reader.userId === userId || reader._id === userId
      );

      if (alreadyRead) {
        return message;
      }

      return {
        ...message,
        readers: [...(message.readers || []), { userId, readAt: timestamp || new Date() }],
      };
    });
  });

  return nextMessages;
};


export const appendIncomingMessage = (messages, incoming) => {
  return insertUniqueChronologicalMessage(messages, incoming);
};

export const appendIncomingNormalizedMessage = (normalizedMessages, incoming) => {
  return insertNormalizedMessage(normalizedMessages, incoming);
};

export const createRoomEventHandlers = ({
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
    onParticipantsUpdate: (participants) => {
      if (!mountedRef.current) return;
      setRoom(prev => ({ ...prev, participants: participants || [] }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current) return;
      if (setNormalizedMessages) {
        setNormalizedMessages(prev => applyReadReceiptsToNormalizedMessages(prev, payload));
        return;
      }
      setMessages(prev => applyReadReceipts(prev, payload));
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (!incoming?._id || processedMessageIds.current.has(incoming._id)) return;
      processedMessageIds.current.add(incoming._id);
      if (setNormalizedMessages) {
        setNormalizedMessages(prev => appendIncomingNormalizedMessage(prev, incoming));
        return;
      }
      setMessages(prev => appendIncomingMessage(prev, incoming));
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
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
