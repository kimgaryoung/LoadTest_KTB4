import { redirect } from 'next/navigation';

/**
 * Keep the legacy /login URL, but redirect before a client page is hydrated.
 * A useEffect redirect cancels an in-flight document navigation, which makes
 * direct clients (including Chromium E2E) intermittently receive ERR_ABORTED.
 */
export default function LoginRedirectPage() {
  redirect('/');
}
