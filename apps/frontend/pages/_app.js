import React from 'react';
import { useRouter } from 'next/router';
import { ThemeProvider } from '@vapor-ui/core';
import '@vapor-ui/core/styles.css';
import '../styles/globals.css';
import ToastContainer from '@/components/Toast';
import { AuthProvider } from '@/contexts/AuthContext';

function MyApp({ Component, pageProps }) {
  const router = useRouter();

  const isErrorPage = router.pathname === '/_error';
  if (isErrorPage) {
    return <Component {...pageProps} />;
  }

  const getLayout = Component.getLayout || ((page) => page);

  return (
    <ThemeProvider defaultTheme="dark">
      <AuthProvider>
        {getLayout(<Component {...pageProps} />)}
        <ToastContainer />
      </AuthProvider>
    </ThemeProvider>
  );
}

export default MyApp;
