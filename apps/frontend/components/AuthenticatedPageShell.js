import React from 'react';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import { SocketProvider } from '@/lib/socket/SocketProvider';

/**
 * Pages Router의 인증 화면에서만 필요한 UI와 Socket.IO 경계를 묶는다.
 * 공개 인증 화면은 이 컴포넌트를 동적으로 불러오지 않아 채팅 코드를
 * 초기 번들에서 제외할 수 있다.
 */
export default function AuthenticatedPageShell({ children }) {
  const { user } = useAuth();

  return (
    <SocketProvider session={user}>
      <ChatHeader />
      {children}
    </SocketProvider>
  );
}
