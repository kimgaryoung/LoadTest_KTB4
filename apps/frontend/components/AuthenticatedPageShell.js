import React from 'react';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import { SocketProvider } from '@/lib/socket/SocketProvider';

/**
 * Pages Router의 보호 화면에만 채팅 UI와 Socket.IO 경계를 추가한다.
 * 각 보호 페이지가 이 레이아웃을 정적으로 참조하므로 공개 인증 화면의
 * 초기 번들에는 포함되지 않고, 동적 청크 로딩이 화면 렌더링을 막지도 않는다.
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
