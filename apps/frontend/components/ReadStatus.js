import React, { useMemo, useRef } from 'react';
import { ConfirmOutlineIcon } from '@vapor-ui/icons';
import { Text, HStack } from '@vapor-ui/core';

const ReadStatus = ({ 
  messageType = 'text',
  participants = [],
  readers = [],
  className = '',
}) => {
  const statusRef = useRef(null);

  // 읽지 않은 참여자 명단 생성 
  const unreadParticipants = useMemo(() => {
    if (messageType === 'system') return [];
    
    return participants.filter(participant => 
      !readers.some(reader => 
        reader.userId === participant._id || 
        reader.userId === participant.id
      )
    );
  }, [participants, readers, messageType]);

  // 읽지 않은 참여자 수 계산
  const unreadCount = useMemo(() => {
    if (messageType === 'system') {
      return 0;
    }
    return unreadParticipants.length;
  }, [unreadParticipants.length, messageType]);

  // 시스템 메시지는 읽음 상태 표시 안 함
  if (messageType === 'system') {
    return null;
  }

  // 모두 읽은 경우
  if (unreadCount === 0) {
    return (
      <HStack
        className={className}
        ref={statusRef}
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
      ref={statusRef}
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
