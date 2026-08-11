import { describe, expect, it, vi } from 'vitest';

vi.mock('next/router', () => ({
  useRouter: () => ({
    query: {},
    push: vi.fn(),
  }),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ login: vi.fn() }),
  withoutAuth: component => component,
}));

import RootLoginPage from '../../pages/index';
import LoginPage from '../../pages/login';

describe('/login page', () => {
  it('renders the same canonical login implementation as the root route', () => {
    expect(LoginPage).toBe(RootLoginPage);
  });
});
