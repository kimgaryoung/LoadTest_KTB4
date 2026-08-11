import { compareMessagesChronologically } from './useMessageList';

export const createNormalizedMessages = () => ({
  ids: [],
  byId: {},
});

export const normalizeMessages = (messages = []) => {
  if (!Array.isArray(messages)) {
    throw new Error('Invalid messages format');
  }

  const state = createNormalizedMessages();

  messages
    .filter(message => message?._id)
    .sort(compareMessagesChronologically)
    .forEach((message) => {
      if (!state.byId[message._id]) {
        state.ids.push(message._id);
      }
      state.byId[message._id] = message;
    });

  return state;
};

export const selectMessagesArray = (normalizedMessages) => (
  normalizedMessages.ids
    .map(id => normalizedMessages.byId[id])
    .filter(Boolean)
);

const isChronologicallySorted = (messages) => {
  for (let index = 1; index < messages.length; index += 1) {
    if (compareMessagesChronologically(messages[index - 1], messages[index]) > 0) {
      return false;
    }
  }

  return true;
};

const insertMessageIdChronologically = (ids, byId, incoming) => {
  if (
    ids.length === 0 ||
    compareMessagesChronologically(byId[ids[ids.length - 1]], incoming) <= 0
  ) {
    return [...ids, incoming._id];
  }

  let low = 0;
  let high = ids.length;

  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (compareMessagesChronologically(byId[ids[middle]], incoming) <= 0) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }

  const nextIds = ids.slice();
  nextIds.splice(low, 0, incoming._id);
  return nextIds;
};

export const mergeNormalizedMessages = (
  current,
  incomingMessages,
  processedMessageIds = new Set()
) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  const processedSnapshot = new Set(processedMessageIds);
  const nextProcessedMessageIds = new Set(processedMessageIds);
  const nextById = { ...current.byId };
  let nextIds = current.ids;
  let changed = false;

  const incoming = isChronologicallySorted(incomingMessages)
    ? incomingMessages
    : [...incomingMessages].sort(compareMessagesChronologically);

  incoming.forEach((message) => {
    if (!message?._id) {
      return;
    }

    nextProcessedMessageIds.add(message._id);

    if (processedSnapshot.has(message._id) || nextById[message._id]) {
      return;
    }

    nextById[message._id] = message;
    nextIds = insertMessageIdChronologically(nextIds, nextById, message);
    processedSnapshot.add(message._id);
    changed = true;
  });

  return {
    messages: changed ? { ids: nextIds, byId: nextById } : current,
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const insertNormalizedMessage = (current, incoming) => {
  if (!incoming?._id || current.byId[incoming._id]) {
    return current;
  }

  const nextById = {
    ...current.byId,
    [incoming._id]: incoming,
  };

  return {
    ids: insertMessageIdChronologically(current.ids, nextById, incoming),
    byId: nextById,
  };
};

export const updateNormalizedMessageById = (current, messageId, updateMessage) => {
  const currentMessage = current.byId[messageId];

  if (!currentMessage) {
    return current;
  }

  const nextMessage = updateMessage(currentMessage);

  if (nextMessage === currentMessage) {
    return current;
  }

  return {
    ids: current.ids,
    byId: {
      ...current.byId,
      [messageId]: nextMessage,
    },
  };
};
