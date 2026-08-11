import { useCallback } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { updateNormalizedMessageById } from '../messages/normalizedMessages';

const replaceMessageReactions = (normalizedMessages, messageId, reactions) => (
  updateNormalizedMessageById(normalizedMessages, messageId, (message) => ({
    ...message,
    reactions,
  }))
);

export const useReactionHandling = ({
  currentUser,
  normalizedMessages,
  setNormalizedMessages,
}) => {
  const getSnapshotReactions = useCallback((messageId) => {
    return normalizedMessages.byId[messageId]?.reactions || {};
  }, [normalizedMessages]);

  const handleReactionAdd = useCallback(async (messageId, reaction) => {
    const previousReactions = getSnapshotReactions(messageId);

    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트
      setNormalizedMessages(prevMessages =>
        updateNormalizedMessageById(prevMessages, messageId, (msg) => {
          const currentReactions = msg.reactions || {};
          const currentUsers = currentReactions[reaction] || [];

          // 중복 추가 방지
          if (currentUsers.includes(currentUser.id)) {
            return msg;
          }

          return {
            ...msg,
            reactions: {
              ...currentReactions,
              [reaction]: [...currentUsers, currentUser.id]
            }
          };
        })
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'add');

    } catch (error) {
      console.error('Add reaction error:', error);
      Toast.error('리액션 추가에 실패했습니다.');

      // 실패 시 롤백
      setNormalizedMessages(prevMessages =>
        replaceMessageReactions(prevMessages, messageId, previousReactions)
      );
    }
  }, [currentUser, getSnapshotReactions, setNormalizedMessages]);

  const handleReactionRemove = useCallback(async (messageId, reaction) => {
    const previousReactions = getSnapshotReactions(messageId);

    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트
      setNormalizedMessages(prevMessages =>
        updateNormalizedMessageById(prevMessages, messageId, (msg) => {
          const currentReactions = msg.reactions || {};
          const currentUsers = currentReactions[reaction] || [];
          return {
            ...msg,
            reactions: {
              ...currentReactions,
              [reaction]: currentUsers.filter(id => id !== currentUser.id)
            }
          };
        })
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'remove');

    } catch (error) {
      console.error('Remove reaction error:', error);
      Toast.error('리액션 제거에 실패했습니다.');

      // 실패 시 롤백
      setNormalizedMessages(prevMessages =>
        replaceMessageReactions(prevMessages, messageId, previousReactions)
      );
    }
  }, [currentUser, getSnapshotReactions, setNormalizedMessages]);

  const handleReactionUpdate = useCallback(({ messageId, reactions }) => {
    setNormalizedMessages(prevMessages =>
      replaceMessageReactions(prevMessages, messageId, reactions)
    );
  }, [setNormalizedMessages]);

  return {
    handleReactionAdd,
    handleReactionRemove,
    handleReactionUpdate
  };
};

export default useReactionHandling;
