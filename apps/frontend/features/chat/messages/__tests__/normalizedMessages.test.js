import { describe, expect, it } from 'vitest';
import {
  createNormalizedMessages,
  insertNormalizedMessage,
  mergeNormalizedMessages,
  normalizeMessages,
  selectMessagesArray,
  updateNormalizedMessageById,
} from '../normalizedMessages';

describe('normalizedMessages', () => {
  it('normalizes messages into chronological ids and byId', () => {
    const normalized = normalizeMessages([
      { _id: 'late', timestamp: 3000, content: 'late' },
      { _id: 'early', timestamp: 1000, content: 'early' },
    ]);

    expect(normalized.ids).toEqual(['early', 'late']);
    expect(normalized.byId.late.content).toBe('late');
    expect(selectMessagesArray(normalized).map(message => message._id)).toEqual([
      'early',
      'late',
    ]);
  });

  it('merges unseen messages without mutating processed ids', () => {
    const processedIds = new Set(['existing']);
    const current = normalizeMessages([
      { _id: 'existing', content: 'middle', timestamp: '2026-01-01T00:00:02Z' },
    ]);

    const result = mergeNormalizedMessages(
      current,
      [
        { _id: 'late', content: 'late', timestamp: '2026-01-01T00:00:03Z' },
        { _id: 'early', content: 'early', timestamp: '2026-01-01T00:00:01Z' },
      ],
      processedIds
    );

    expect(result.messages.ids).toEqual(['early', 'existing', 'late']);
    expect(selectMessagesArray(result.messages).map(message => message.content)).toEqual([
      'early',
      'middle',
      'late',
    ]);
    expect(processedIds).toEqual(new Set(['existing']));
    expect(result.processedMessageIds).toEqual(
      new Set(['existing', 'late', 'early'])
    );
  });

  it('ignores duplicates and messages without ids', () => {
    const current = normalizeMessages([
      { _id: 'duplicate', content: 'original', timestamp: 1000 },
    ]);

    const result = mergeNormalizedMessages(
      current,
      [
        { _id: 'duplicate', content: 'newer duplicate', timestamp: 2000 },
        { content: 'missing id', timestamp: 3000 },
      ],
      new Set(['duplicate'])
    );

    expect(result.messages).toBe(current);
    expect(selectMessagesArray(result.messages)).toEqual([
      { _id: 'duplicate', content: 'original', timestamp: 1000 },
    ]);
  });

  it('inserts delayed live messages without reordering equal timestamps', () => {
    const current = normalizeMessages([
      { _id: 'first', timestamp: 1000 },
      { _id: 'same-time', timestamp: 3000 },
      { _id: 'last', timestamp: 5000 },
    ]);

    const withDelayedMessage = insertNormalizedMessage(
      current,
      { _id: 'delayed', timestamp: 2000 }
    );
    const withEqualTimestamp = insertNormalizedMessage(
      withDelayedMessage,
      { _id: 'same-time-later', timestamp: 3000 }
    );

    expect(withEqualTimestamp.ids).toEqual([
      'first',
      'delayed',
      'same-time',
      'same-time-later',
      'last',
    ]);
  });

  it('updates one message by id while preserving ids identity', () => {
    const current = normalizeMessages([
      { _id: 'message-1', reactions: {} },
      { _id: 'message-2', reactions: { '👍': ['user-2'] } },
    ]);

    const updated = updateNormalizedMessageById(
      current,
      'message-2',
      message => ({
        ...message,
        reactions: {
          ...message.reactions,
          '👍': [...message.reactions['👍'], 'user-1'],
        },
      })
    );

    expect(updated.ids).toBe(current.ids);
    expect(updated.byId['message-1']).toBe(current.byId['message-1']);
    expect(updated.byId['message-2'].reactions['👍']).toEqual(['user-2', 'user-1']);
  });

  it('returns the same state for missing update targets', () => {
    const current = createNormalizedMessages();

    expect(
      updateNormalizedMessageById(current, 'missing', message => message)
    ).toBe(current);
  });

  it('throws for invalid incoming payloads', () => {
    expect(() => normalizeMessages(null)).toThrow('Invalid messages format');
    expect(() => mergeNormalizedMessages(createNormalizedMessages(), null)).toThrow(
      'Invalid messages format'
    );
  });
});
