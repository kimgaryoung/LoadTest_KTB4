import { useState, useCallback } from 'react';
import axiosInstance from '@/services/axios';
import { HEALTH_TIMEOUT_MS } from '@/lib/api/client';

export const CONNECTION_STATUS = {
  CHECKING: 'checking',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const RETRY_CONFIG = {
  maxRetries: 1,
  baseDelay: 3000,
  maxDelay: 3000,
  backoffFactor: 2,
  reconnectInterval: 30000,
};

export const useServerConnection = () => {
  const [connectionStatus, setConnectionStatus] = useState(CONNECTION_STATUS.CHECKING);
  const [retryCount, setRetryCount] = useState(0);
  const [isRetrying, setIsRetrying] = useState(false);

  const getRetryDelay = useCallback((count) => {
    const delay =
      RETRY_CONFIG.baseDelay *
      Math.pow(RETRY_CONFIG.backoffFactor, count) *
      (1 + Math.random() * 0.1);
    return Math.min(delay, RETRY_CONFIG.maxDelay);
  }, []);

  const attemptConnection = useCallback(async () => {
    for (let retryAttempt = 0; retryAttempt <= RETRY_CONFIG.maxRetries; retryAttempt += 1) {
      try {
        setConnectionStatus(CONNECTION_STATUS.CONNECTING);

        const response = await axiosInstance.get('/api/health', {
          timeout: HEALTH_TIMEOUT_MS,
          // Health check retries are owned by this hook. Do not also apply the
          // API client's generic retry policy, which multiplies wait time.
          retry: false,
        });

        if (response?.status === 401) {
          setConnectionStatus(CONNECTION_STATUS.ERROR);
          throw new Error('AUTH_EXPIRED');
        }

        const isConnected =
          response?.data?.status === 'ok' && response?.status === 200;

        if (isConnected) {
          setConnectionStatus(CONNECTION_STATUS.CONNECTED);
          setRetryCount(0);
          return true;
        }

        throw new Error('Server not ready');
      } catch (error) {
        if (error.response?.status === 401 || error.message === 'AUTH_EXPIRED') {
          setConnectionStatus(CONNECTION_STATUS.ERROR);
          throw new Error('AUTH_EXPIRED');
        }

        if (!error.response && retryAttempt < RETRY_CONFIG.maxRetries) {
          const delay = getRetryDelay(retryAttempt);
          await new Promise((resolve) => setTimeout(resolve, delay));
          continue;
        }

        setConnectionStatus(CONNECTION_STATUS.ERROR);
        throw new Error('SERVER_UNREACHABLE');
      }
    }

    throw new Error('SERVER_UNREACHABLE');
  }, [getRetryDelay]);

  return {
    connectionStatus,
    setConnectionStatus,
    retryCount,
    setRetryCount,
    isRetrying,
    setIsRetrying,
    getRetryDelay,
    attemptConnection,
  };
};

export default useServerConnection;
