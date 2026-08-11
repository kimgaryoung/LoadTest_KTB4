import React from 'react';
import dynamic from 'next/dynamic';
import { useRouter } from 'next/router';
import { ThemeProvider } from '@vapor-ui/core';
import '@vapor-ui/core/styles.css';
import '../styles/globals.css';
import ToastContainer from '@/components/Toast';
import { AuthProvider } from '@/contexts/AuthContext';

const AuthenticatedPageShell = dynamic(
  () => import('@/components/AuthenticatedPageShell')
);

const PUBLIC_ROUTES = new Set(['/', '/login', '/register']);

function MyApp({ Component, pageProps }) {
  const router = useRouter();

  const isErrorPage = router.pathname === '/_error';
  if (isErrorPage) {
    return <Component {...pageProps} />;
  }

  const isPublicRoute = PUBLIC_ROUTES.has(router.pathname);

  return (
    <ThemeProvider defaultTheme="dark">
      <AuthProvider>
        {isPublicRoute ? (
          <Component {...pageProps} />
        ) : (
          <AuthenticatedPageShell>
            <Component {...pageProps} />
          </AuthenticatedPageShell>
        )}
        <ToastContainer />
      </AuthProvider>
    </ThemeProvider>
  );
}

export default MyApp;
