import { beforeEach, describe, expect, it, vi } from 'vitest';

const { post } = vi.hoisted(() => ({
  post: vi.fn(),
}));

vi.mock('../../lib/api/client', () => ({
  default: { post },
  getAuthHeaders: vi.fn(),
  HEALTH_TIMEOUT_MS: 3000,
}));

import authService from '../authService';

describe('authService retry policy', () => {
  beforeEach(() => {
    post.mockReset();
  });

  it('disables automatic retries for login', async () => {
    const credentials = {
      email: 'loadtest@example.com',
      password: 'Password1!',
    };
    post.mockResolvedValue({
      data: {
        success: true,
        token: 'token-1',
        sessionId: 'session-1',
        user: {
          _id: 'user-1',
          name: 'Load Test',
          email: credentials.email,
        },
      },
    });

    await authService.login(credentials);

    expect(post).toHaveBeenCalledWith('/api/auth/login', credentials, {
      skipAuth: true,
      handleAuthError: false,
      retry: false,
    });
  });

  it('disables automatic retries for registration', async () => {
    const userData = {
      name: 'Load Test',
      email: 'loadtest@example.com',
      password: 'Password1!',
    };
    post.mockResolvedValue({
      data: { success: true },
    });

    await authService.register(userData);

    expect(post).toHaveBeenCalledWith('/api/auth/register', userData, {
      skipAuth: true,
      retry: false,
    });
  });
});
