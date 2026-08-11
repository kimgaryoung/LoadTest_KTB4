import { performance } from 'node:perf_hooks';
import { describe, expect, it } from 'vitest';

const CURRENT_USER_ID = 'user-1';
const REACTION = '👍';
const UPDATE_ITERATIONS = 1_000;
const MESSAGE_COUNTS = [100, 1_000, 10_000];

const createMessages = (count) => Array.from({ length: count }, (_, index) => ({
  _id: `message-${index}`,
  content: `message ${index}`,
  reactions: index % 3 === 0 ? { [REACTION]: ['user-2'] } : {},
}));

const updateReactionByArrayScan = (messages, messageId, reaction, userId) => (
  messages.map((message) => {
    if (message._id !== messageId) {
      return message;
    }

    const currentReactions = message.reactions || {};
    const currentUsers = currentReactions[reaction] || [];

    if (currentUsers.includes(userId)) {
      return message;
    }

    return {
      ...message,
      reactions: {
        ...currentReactions,
        [reaction]: [...currentUsers, userId],
      },
    };
  })
);

const buildMessageIndexById = (messages) => {
  const indexById = new Map();
  messages.forEach((message, index) => {
    if (message?._id) {
      indexById.set(message._id, index);
    }
  });
  return indexById;
};

const updateReactionByMessageIdMap = (messages, messageIndexById, messageId, reaction, userId) => {
  const messageIndex = messageIndexById.get(messageId);
  if (messageIndex === undefined) {
    return messages;
  }

  const message = messages[messageIndex];
  const currentReactions = message.reactions || {};
  const currentUsers = currentReactions[reaction] || [];

  if (currentUsers.includes(userId)) {
    return messages;
  }

  const nextMessages = messages.slice();
  nextMessages[messageIndex] = {
    ...message,
    reactions: {
      ...currentReactions,
      [reaction]: [...currentUsers, userId],
    },
  };
  return nextMessages;
};

const measureUpdate = (update) => {
  const startedAt = performance.now();
  for (let iteration = 0; iteration < UPDATE_ITERATIONS; iteration += 1) {
    update(iteration);
  }
  return performance.now() - startedAt;
};

describe('reaction message ID map performance baseline', () => {
  it('compares reaction update cost against full-array scan', () => {
    const rows = MESSAGE_COUNTS.map((messageCount) => {
      const messages = createMessages(messageCount);
      const targetMessageId = `message-${messageCount - 1}`;
      const messageIndexById = buildMessageIndexById(messages);

      const arrayScanMs = measureUpdate(() => {
        updateReactionByArrayScan(messages, targetMessageId, REACTION, CURRENT_USER_ID);
      });
      const messageIdMapMs = measureUpdate(() => {
        updateReactionByMessageIdMap(
          messages,
          messageIndexById,
          targetMessageId,
          REACTION,
          CURRENT_USER_ID
        );
      });

      return {
        messages: messageCount,
        iterations: UPDATE_ITERATIONS,
        arrayScanMs: Number(arrayScanMs.toFixed(2)),
        messageIdMapMs: Number(messageIdMapMs.toFixed(2)),
        speedup: Number((arrayScanMs / messageIdMapMs).toFixed(2)),
      };
    });

    console.table(rows);

    const sampleMessages = createMessages(3);
    const sampleIndexById = buildMessageIndexById(sampleMessages);
    const updatedMessages = updateReactionByMessageIdMap(
      sampleMessages,
      sampleIndexById,
      'message-1',
      REACTION,
      CURRENT_USER_ID
    );

    expect(updatedMessages).not.toBe(sampleMessages);
    expect(updatedMessages[1].reactions[REACTION]).toContain(CURRENT_USER_ID);
    expect(updatedMessages[0]).toBe(sampleMessages[0]);
    expect(updatedMessages[2]).toBe(sampleMessages[2]);
  });
});
