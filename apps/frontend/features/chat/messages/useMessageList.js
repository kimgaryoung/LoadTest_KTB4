const timestampOf = (message) => {
  const timestamp = message?.timestamp;

  if (typeof timestamp === 'number') {
    return Number.isFinite(timestamp) ? timestamp : Number.POSITIVE_INFINITY;
  }

  if (!timestamp) {
    return Number.POSITIVE_INFINITY;
  }

  const parsedTimestamp = Date.parse(timestamp);
  return Number.isNaN(parsedTimestamp) ? Number.POSITIVE_INFINITY : parsedTimestamp;
};

export const compareMessagesChronologically = (left, right) => {
  const leftTimestamp = timestampOf(left);
  const rightTimestamp = timestampOf(right);

  if (leftTimestamp === rightTimestamp) {
    return 0;
  }

  return leftTimestamp - rightTimestamp;
};

const isChronologicallySorted = (messages) => {
  for (let index = 1; index < messages.length; index += 1) {
    if (compareMessagesChronologically(messages[index - 1], messages[index]) > 0) {
      return false;
    }
  }

  return true;
};

const mergeChronologicalMessages = (currentMessages, incomingMessages) => {
  const mergedMessages = [];
  let currentIndex = 0;
  let incomingIndex = 0;

  while (currentIndex < currentMessages.length && incomingIndex < incomingMessages.length) {
    if (
      compareMessagesChronologically(
        currentMessages[currentIndex],
        incomingMessages[incomingIndex]
      ) <= 0
    ) {
      mergedMessages.push(currentMessages[currentIndex]);
      currentIndex += 1;
    } else {
      mergedMessages.push(incomingMessages[incomingIndex]);
      incomingIndex += 1;
    }
  }

  while (currentIndex < currentMessages.length) {
    mergedMessages.push(currentMessages[currentIndex]);
    currentIndex += 1;
  }

  while (incomingIndex < incomingMessages.length) {
    mergedMessages.push(incomingMessages[incomingIndex]);
    incomingIndex += 1;
  }

  return mergedMessages;
};

export const deriveUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  const processedSnapshot = new Set(processedMessageIds);
  const nextProcessedMessageIds = new Set(processedMessageIds);
  const currentMessageIds = new Set(
    currentMessages.map(message => message?._id).filter(Boolean)
  );
  const newMessages = incomingMessages.filter((message) => {
    if (!message._id) {
      return false;
    }

    nextProcessedMessageIds.add(message._id);

    if (processedSnapshot.has(message._id) || currentMessageIds.has(message._id)) {
      return false;
    }

    processedSnapshot.add(message._id);
    currentMessageIds.add(message._id);
    return true;
  });

  if (newMessages.length === 0) {
    return {
      messages: currentMessages,
      processedMessageIds: nextProcessedMessageIds,
    };
  }

  // 서버의 메시지 페이지는 ASC 순서다. 계약을 벗어난 입력만 작은 incoming
  // batch 안에서 정렬하고, 전체 메시지 목록은 선형 병합한다.
  const chronologicalIncoming = isChronologicallySorted(newMessages)
    ? newMessages
    : [...newMessages].sort(compareMessagesChronologically);

  return {
    messages: mergeChronologicalMessages(currentMessages, chronologicalIncoming),
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const insertUniqueChronologicalMessage = (messages, incoming) => {
  if (!incoming?._id || messages.some(message => message._id === incoming._id)) {
    return messages;
  }

  if (
    messages.length === 0 ||
    compareMessagesChronologically(messages[messages.length - 1], incoming) <= 0
  ) {
    return [...messages, incoming];
  }

  let low = 0;
  let high = messages.length;

  // 같은 timestamp의 기존 메시지는 유지하고 그 뒤에 새 메시지를 넣는다.
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (compareMessagesChronologically(messages[middle], incoming) <= 0) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }

  const nextMessages = [...messages];
  nextMessages.splice(low, 0, incoming);
  return nextMessages;
};

export const mergeUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  return deriveUniqueSortedMessages(
    currentMessages,
    incomingMessages,
    processedMessageIds
  ).messages;
};
