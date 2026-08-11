import { useRef, useEffect, useCallback } from 'react';
import socketClient from '@/lib/socket/socketClient';
import { useAuth } from '@/contexts/AuthContext';
import { Toast } from '@/components/Toast';
import api, { getAuthHeaders } from '@/lib/api/client';
import {
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from './roomEventHandlers';

export const useRoomHandling = ({
  roomId,
  route,
  state,
  refs,
  actions,
  cleanup,
  handleReactionUpdate,
  activeSocket,
}) => {
  const { onReplace, asPath } = route;
  const { currentUser } = state;
  const {
    socketRef,
    attachSocket,
    mountedRef,
    initializingRef,
    setupCompleteRef,
    userRooms,
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
  } = refs;
  const {
    setRoom,
    setError,
    setMessages,
    setHasMoreMessages,
    setLoadingMessages,
    setupStarted,
    setupSucceeded,
    setupFailed,
  } = actions;
  const { user, refreshToken, logout } = useAuth();
  const setupPromiseRef = useRef(null);
  const roomEventsUnsubscribeRef = useRef(null);
  const roomEventsSocketRef = useRef(null);
  const roomEventsRoomIdRef = useRef(null);
  const MAX_SOCKET_RECONNECT_ATTEMPTS = 3;
  const MAX_MESSAGE_RETRY_ATTEMPTS = 3;
  const MESSAGE_TIMEOUT = 5000;
  const MESSAGE_RETRY_DELAY = 2000;

  const processMessages = useCallback(
    (loadedMessages, hasMore, isInitialLoad = false) => {
      processLoadedRoomMessages({
        loadedMessages,
        hasMore,
        isInitialLoad,
        processedMessageIds,
        setMessages,
        setHasMoreMessages,
        initialLoadCompletedRef,
      });
    },
    [
      processedMessageIds,
      setMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    ]
  );

  const roomEventCallbacksRef = useRef({
    processMessages,
    cleanup,
    logout,
    onReplace,
    handleReactionUpdate,
  });

  useEffect(() => {
    roomEventCallbacksRef.current = {
      processMessages,
      cleanup,
      logout,
      onReplace,
      handleReactionUpdate,
    };
  }, [cleanup, handleReactionUpdate, logout, onReplace, processMessages]);

  const unsubscribeRoomEvents = useCallback(() => {
    roomEventsUnsubscribeRef.current?.();
    roomEventsUnsubscribeRef.current = null;
    roomEventsSocketRef.current = null;
    roomEventsRoomIdRef.current = null;
  }, []);

  const setupEventListeners = useCallback((socket = socketRef.current) => {
    if (!socket?.connected || !mountedRef.current) return;

    if (
      roomEventsSocketRef.current === socket &&
      roomEventsRoomIdRef.current === roomId &&
      roomEventsUnsubscribeRef.current
    ) {
      return;
    }

    unsubscribeRoomEvents();
    roomEventsUnsubscribeRef.current = socketClient.subscribeRoomEvents(
      socket,
      createRoomEventHandlers({
        roomId,
        mountedRef,
        messageProcessingRef,
        processedMessageIds,
        initialLoadCompletedRef,
        processMessages: (...args) => roomEventCallbacksRef.current.processMessages(...args),
        setRoom,
        setMessages,
        setLoadingMessages,
        setError,
        setHasMoreMessages,
        cleanup: (...args) => roomEventCallbacksRef.current.cleanup(...args),
        logout: (...args) => roomEventCallbacksRef.current.logout(...args),
        onReplace: (...args) => roomEventCallbacksRef.current.onReplace(...args),
        handleReactionUpdate: (...args) => roomEventCallbacksRef.current.handleReactionUpdate(...args),
        showRejectedMessage: Toast.error.bind(Toast),
      })
    );
    roomEventsSocketRef.current = socket;
    roomEventsRoomIdRef.current = roomId;
  }, [
    roomId,
    setHasMoreMessages,
    setLoadingMessages,
    setError,
    socketRef,
    mountedRef,
    messageProcessingRef,
    processedMessageIds,
    initialLoadCompletedRef,
    setRoom,
    setMessages,
    unsubscribeRoomEvents,
  ]);

  useEffect(() => {
    setupEventListeners(activeSocket);
  }, [activeSocket, setupEventListeners]);

  const handleSessionError = useCallback(async () => {
    try {
      if (!user) {
        throw new Error('No user session found');
      }

      await refreshToken();
      if (mountedRef.current) {
        return true;
      }
    } catch (error) {}

    if (mountedRef.current) {
      await logout();
      onReplace('/?redirect=' + asPath);
    }
    return false;
  }, [user, refreshToken, mountedRef, logout, onReplace, asPath]);

  const setupSocket = useCallback(async () => {
    try {
      if (!user?.token || !user?.sessionId) {
        throw new Error('Invalid authentication state');
      }

      if (socketRef.current?.connected) {
        return socketRef.current;
      }

      if (socketRef.current) {
        const currentSocket = socketRef.current;

        if (userRooms.current?.get(currentSocket.id)) {
          await new Promise((resolve) => {
            socketClient.leaveRoom(
              userRooms.current.get(currentSocket.id),
              currentSocket
            );
            setTimeout(resolve, 1000);
          });
          userRooms.current.delete(currentSocket.id);
        }

        currentSocket.disconnect();
        currentSocket.removeAllListeners();
        attachSocket(null);

        await new Promise((resolve) => setTimeout(resolve, 2000));
      }

      const socket = await socketClient.connect({
        auth: {
          token: user.token,
          sessionId: user.sessionId,
        },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: MAX_SOCKET_RECONNECT_ATTEMPTS,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 3000,
        timeout: 10000,
        pingTimeout: 10000,
        pingInterval: 8000,
        forceNew: true,
        autoConnect: true,
      });

      return socket;
    } catch (error) {
      if (error.message === 'Invalid authentication state') {
        onReplace('/?error=auth_required');
      }
      throw error;
    }
  }, [userRooms, onReplace, socketRef, attachSocket, user]);

  const fetchRoomData = useCallback(
    async (roomId) => {
      try {
        if (!user?.token || !user?.sessionId) {
          await handleSessionError();
          throw new Error('인증 정보가 유효하지 않습니다.');
        }

        if (!roomId || !mountedRef.current) {
          throw new Error('채팅방 정보가 올바르지 않습니다.');
        }

        let response;
        try {
          response = await api.get(`/api/rooms/${roomId}`, {
            handleAuthError: false,
            headers: getAuthHeaders(user),
          });
        } catch (error) {
          if (error.response?.status === 401) {
            const refreshed = await handleSessionError();
            if (refreshed && mountedRef.current) {
              return fetchRoomData(roomId);
            }
            throw new Error('인증이 만료되었습니다.');
          }
          throw error;
        }

        const data = response.data;
        if (!data.success || !data.data) {
          throw new Error('채팅방 데이터가 올바르지 않습니다.');
        }

        return data.data;
      } catch (error) {
        throw error;
      }
    },
    [user, mountedRef, handleSessionError]
  );

  const joinRoom = useCallback(
    async (roomId, socket) => {
      if (!roomId || !mountedRef.current) {
        throw new Error('잘못된 채팅방 정보입니다.');
      }

      if (!socket?.connected) {
        throw new Error('Socket not connected');
      }

      const data = await socketClient.joinRoomAndWait(roomId, socket);
      userRooms.current?.set(socket.id, roomId);
      return data;
    },
    [mountedRef, userRooms]
  );

  // 재연결 뒤 필요한 것은 방 참가 상태 복구뿐이다. socket.io 가 같은 소켓을
  // 되살렸으므로 방 이벤트 구독도 그대로 살아 있다 — 여기서 소켓을 새로 만들면
  // 살아 있는 연결을 버리는 셈이 된다.
  const rejoinRoom = useCallback(async () => {
    const socket = socketRef.current;
    if (!roomId || !mountedRef.current || !socket?.connected) {
      return;
    }

    const joinResult = await joinRoom(roomId, socket);

    if (Array.isArray(joinResult?.messages)) {
      processMessages(joinResult.messages, joinResult.hasMore, true);
    }

    if (mountedRef.current) {
      setupCompleteRef.current = true;
    }
  }, [
    roomId,
    socketRef,
    mountedRef,
    setupCompleteRef,
    joinRoom,
    processMessages,
  ]);

  const loadInitialMessages = useCallback(
    async (roomId) => {
      const loadMessagesWithRetry = async (retryCount = 0) => {
        const socket = socketRef.current;
        if (!socket?.connected) {
          throw new Error('Socket not connected');
        }

        try {
          const response = await socketClient.fetchPreviousMessagesAndWait(
            { roomId, limit: 30 },
            socket,
            { timeoutMs: MESSAGE_TIMEOUT }
          );

          if (!response || !Array.isArray(response.messages)) {
            throw new Error('잘못된 메시지 응답 형식입니다.');
          }

          processMessages(response.messages, response.hasMore, true);
          return response;
        } catch (error) {
          if (retryCount < MAX_MESSAGE_RETRY_ATTEMPTS) {
            await new Promise((resolve) =>
              setTimeout(resolve, MESSAGE_RETRY_DELAY)
            );
            return loadMessagesWithRetry(retryCount + 1);
          }

          throw error;
        }
      };

      try {
        return await loadMessagesWithRetry();
      } catch (error) {
        if (!socketRef.current?.connected) {
          // setupSocket 은 낡은 소켓을 버리고 새 소켓을 반환한다. 받아서 걸어주지
          // 않으면 ref 가 비어 있어 재시도가 곧바로 'Socket not connected' 로 죽는다.
          attachSocket(await setupSocket());
          return loadMessagesWithRetry();
        }
        throw error;
      }
    },
    [socketRef, attachSocket, processMessages, setupSocket]
  );

  const setupRoom = useCallback(async () => {
    if (setupPromiseRef.current) {
      return setupPromiseRef.current;
    }

    setupPromiseRef.current = (async () => {
      try {
        initializingRef.current = true;
        setupStarted();
        // Socket connection and room metadata are independent setup operations.
        let setupCancelled = false;
        const socketPromise = setupSocket().then((socket) => {
          if (setupCancelled || !mountedRef.current) {
            socket.disconnect();
            throw new Error('Chat room setup cancelled');
          }
          return socket;
        });

        let socket;
        let roomData;
        try {
          [socket, roomData] = await Promise.all([
            socketPromise,
            fetchRoomData(roomId),
          ]);
        } catch (error) {
          setupCancelled = true;
          socketPromise
            .then((connectedSocket) => connectedSocket.disconnect())
            .catch(() => {});
          throw error;
        }

        if (!mountedRef.current) {
          socket.disconnect();
          return;
        }

        attachSocket(socket);

        // Ensure current user is included in participants for display
        if (currentUser && roomData.participants) {
          const isUserInParticipants = roomData.participants.some(
            (p) => p._id === currentUser.id || p.id === currentUser.id
          );

          if (!isUserInParticipants) {
            roomData.participants = [
              ...roomData.participants,
              {
                _id: currentUser.id,
                id: currentUser.id,
                name: currentUser.name,
                email: currentUser.email,
              },
            ];
          }
        }

        // Setup Event Listeners
        if (mountedRef.current) {
          setupEventListeners(socket);
        }

        // Join Room and Load Messages
        if (mountedRef.current && socket.connected) {
          const joinResult = await joinRoom(roomId, socket);

          if (Array.isArray(joinResult?.participants)) {
            roomData.participants = joinResult.participants;
          }

          if (Array.isArray(joinResult?.messages)) {
            processMessages(joinResult.messages, joinResult.hasMore, true);
          } else {
            await loadInitialMessages(roomId);
          }
        }

        if (mountedRef.current) {
          setupCompleteRef.current = true;
          setupSucceeded(roomData);
        }
      } catch (error) {
        if (mountedRef.current) {
          const errorMessage = error.message.includes('시간 초과')
            ? '채팅방 연결 시간이 초과되었습니다.'
            : error.message || '채팅방 연결에 실패했습니다.';

          setupFailed(errorMessage);
          cleanup('ERROR');

          if (socketRef.current) {
            socketRef.current.disconnect();
            attachSocket(null);
          }
        }

        throw error;
      } finally {
        if (mountedRef.current) {
          initializingRef.current = false;
        }

        setupPromiseRef.current = null;
      }
    })();

    return setupPromiseRef.current;
  }, [
    roomId,
    socketRef,
    attachSocket,
    mountedRef,
    setupSocket,
    fetchRoomData,
    joinRoom,
    loadInitialMessages,
    processMessages,
    cleanup,
    setupEventListeners,
    setupStarted,
    setupSucceeded,
    setupFailed,
    currentUser,
    initializingRef,
    setupCompleteRef,
  ]);

  useEffect(() => {
    const trackedUserRooms = userRooms.current;

    return () => {
      setupPromiseRef.current = null;
      initializingRef.current = false;
      setupCompleteRef.current = false;

      unsubscribeRoomEvents();

      // 언마운트 경로는 attachSocket 을 쓰지 않는다. 사라지는 컴포넌트에
      // 소켓 교체를 통지할 구독자가 없다.
      const socket = socketRef.current;
      if (socket) {
        if (socket.connected && trackedUserRooms?.get(socket.id) === roomId) {
          roomEventCallbacksRef.current.cleanup('unmount');
          trackedUserRooms.delete(socket.id);
        }

        socket.disconnect();
        socketRef.current = null;
      }
    };
  }, [
    roomId,
    socketRef,
    userRooms,
    initializingRef,
    setupCompleteRef,
    unsubscribeRoomEvents,
  ]);

  return {
    setupRoom,
    rejoinRoom,
    loadInitialMessages,
  };
};

export default useRoomHandling;
