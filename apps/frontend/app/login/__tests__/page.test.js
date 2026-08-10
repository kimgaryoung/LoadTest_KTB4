import { beforeEach, describe, expect, it, vi } from 'vitest';

const { redirectMock } = vi.hoisted(() => ({
  redirectMock: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  redirect: redirectMock,
}));

import LoginRedirectPage, { buildLoginTarget } from '../page';

describe('LoginRedirectPage', () => {
  beforeEach(() => {
    redirectMock.mockReset();
  });

  it('redirects /login to the login screen at / on the server', async () => {
    await LoginRedirectPage({ searchParams: Promise.resolve({}) });

    expect(redirectMock).toHaveBeenCalledWith('/');
  });

  it('preserves query parameters while redirecting', async () => {
    await LoginRedirectPage({
      searchParams: Promise.resolve({
        redirect: '/chat',
        source: ['invite', 'notification'],
      }),
    });

    expect(redirectMock).toHaveBeenCalledWith(
      '/?redirect=%2Fchat&source=invite&source=notification',
    );
  });

  it('builds the same target for synchronous search params', () => {
    expect(buildLoginTarget({ redirect: '/profile' })).toBe(
      '/?redirect=%2Fprofile',
    );
  });
});
