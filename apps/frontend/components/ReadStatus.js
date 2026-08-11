import React, { useMemo } from 'react';
import { ConfirmOutlineIcon } from '@vapor-ui/icons';
import { Text, HStack } from '@vapor-ui/core';

const EMPTY_PARTICIPANTS = [];
const EMPTY_READERS = [];

const ReadStatus = ({
  messageType = 'text',
  participants = EMPTY_PARTICIPANTS,
  readers = EMPTY_READERS,
  senderId = null,
  className = '',
}) => {
  const participantList = Array.isArray(participants) ? participants : EMPTY_PARTICIPANTS;

  const unreadCount = useMemo(() => {
    if (messageType === 'system') {
      return 0;
    }

    const readUserIds = new Set(readers.flatMap(reader => (
      [reader?.userId, reader?._id, reader?.id].filter(Boolean)
    )));

    return participantList.reduce((count, participant) => {
      const participantId = participant._id || participant.id;

      // The sender does not need to mark their own message as read.
      if (senderId && participantId === senderId) {
        return count;
      }

      return readUserIds.has(participant._id) || readUserIds.has(participant.id)
        ? count
        : count + 1;
    }, 0);
  }, [participantList, readers, messageType, senderId]);

  // 시스템 메시지는 읽음 상태 표시 안 함
  if (messageType === 'system') {
    return null;
  }

  // 모두 읽은 경우
  if (unreadCount === 0) {
    return (
      <HStack
        className={className}
        $css={{ gap: '$050', alignItems: 'center' }}
        role="status"
        aria-label="모든 참여자가 메시지를 읽었습니다"
        data-testid="read-status-all-read"
      >
        <HStack $css={{ alignItems: 'center' }}>
          <ConfirmOutlineIcon size={12} className='text-v-success-100' />
          <ConfirmOutlineIcon size={12} className='-ml-1.5 text-v-success-100' />
        </HStack>
        <Text typography="subtitle2" className="text-v-hint-200">모두 읽음</Text>
      </HStack>
    );
  }

  // 읽지 않은 사람이 있는 경우
  return (
    <HStack
      className={className}
      $css={{ gap: '$050', alignItems: 'center' }}
      role="status"
      aria-label={`${unreadCount}명이 메시지를 읽지 않았습니다`}
      data-testid="read-status-unread"
    >
      <ConfirmOutlineIcon size={12} className="text-v-hint-200" />
      {unreadCount > 0 && (
        <Text typography="subtitle2" className="text-v-hint-200">
          {unreadCount}명 안 읽음
        </Text>
      )}
    </HStack>
  );
};

export default ReadStatus;
