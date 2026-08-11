package com.ktb.chatapp.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionStore;
import com.ktb.chatapp.service.session.SessionTouchResult;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService 단위 테스트")
class SessionServiceUnitTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-1";

    @Mock
    private SessionStore sessionStore;

    @InjectMocks
    private SessionService sessionService;

    @Test
    @DisplayName("세션 생성은 사용자 세션을 atomic upsert로 한 번에 교체한다")
    void createSession_AtomicallyReplacesUserSession() {
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        when(sessionStore.replaceByUserId(any(Session.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SessionCreationResult result = sessionService.createSession(
                USER_ID,
                new SessionMetadata("agent", "127.0.0.1", "device"));

        verify(sessionStore, never()).deleteAll(USER_ID);
        verify(sessionStore).replaceByUserId(sessionCaptor.capture());
        Session savedSession = sessionCaptor.getValue();
        assertThat(result.getSessionId()).isEqualTo(savedSession.getSessionId());
        assertThat(result.getExpiresIn()).isEqualTo(SessionService.SESSION_TTL_SEC);
        assertThat(result.getSessionData().getUserId()).isEqualTo(USER_ID);
        assertThat(savedSession.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("세션 생성 중 저장소 실패는 RuntimeException으로 래핑된다")
    void createSession_StoreFailure_ThrowsRuntimeException() {
        doThrow(new IllegalStateException("store down"))
                .when(sessionStore).replaceByUserId(any(Session.class));

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> sessionService.createSession(USER_ID, null));

        assertThat(exception).hasMessage("세션 생성 중 오류가 발생했습니다.");
        assertThat(exception).hasRootCauseInstanceOf(IllegalStateException.class);
        verify(sessionStore, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 검증은 null 입력을 저장소 조회 없이 거부한다")
    void validateSession_NullInputs_ReturnsInvalidParameters() {
        SessionValidationResult missingUser = sessionService.validateSession(null, SESSION_ID);
        SessionValidationResult missingSession = sessionService.validateSession(USER_ID, null);

        assertThat(missingUser.isValid()).isFalse();
        assertThat(missingUser.getError()).isEqualTo("INVALID_PARAMETERS");
        assertThat(missingSession.isValid()).isFalse();
        assertThat(missingSession.getError()).isEqualTo("INVALID_PARAMETERS");
        verify(sessionStore, never()).validateAndTouch(anyString(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("세션 검증은 누락된 세션을 INVALID_SESSION으로 반환한다")
    void validateSession_MissingSession_ReturnsInvalidSession() {
        when(sessionStore.validateAndTouch(
                anyString(), anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(SessionTouchResult.invalid(SessionTouchResult.Status.NOT_FOUND));

        SessionValidationResult result = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).isEqualTo("INVALID_SESSION");
        verify(sessionStore, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 검증은 만료된 세션을 제거하고 SESSION_EXPIRED로 반환한다")
    void validateSession_ExpiredSession_RemovesSession() {
        when(sessionStore.validateAndTouch(
                anyString(), anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(SessionTouchResult.invalid(SessionTouchResult.Status.EXPIRED));

        SessionValidationResult result = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).isEqualTo("SESSION_EXPIRED");
        verify(sessionStore, never()).delete(USER_ID, SESSION_ID);
        verify(sessionStore, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 검증 중 저장소 실패는 VALIDATION_ERROR로 반환한다")
    void validateSession_StoreFailure_ReturnsValidationError() {
        when(sessionStore.validateAndTouch(
                anyString(), anyString(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("store down"));

        SessionValidationResult result = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("같은 세션의 연속 검증은 1초 캐시로 저장소 접근을 한 번만 수행한다")
    void validateSession_RepeatedRequest_UsesValidationCache() {
        long now = Instant.now().toEpochMilli();
        Session session = Session.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .createdAt(now)
                .lastActivity(now)
                .expiresAt(Instant.now().plusSeconds(SessionService.SESSION_TTL_SEC))
                .build();
        when(sessionStore.validateAndTouch(
                anyString(), anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(SessionTouchResult.valid(session));

        SessionValidationResult first = sessionService.validateSession(USER_ID, SESSION_ID);
        SessionValidationResult second = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(first.isValid()).isTrue();
        assertThat(second.isValid()).isTrue();
        verify(sessionStore, times(1)).validateAndTouch(
                anyString(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("세션 검증 캐시는 최대 1만 개로 제한된다")
    @SuppressWarnings("unchecked")
    void validationCache_IsBounded() {
        Cache<String, SessionData> cache =
                (Cache<String, SessionData>) ReflectionTestUtils.getField(sessionService, "validationCache");
        SessionData sessionData = SessionData.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .createdAt(1L)
                .lastActivity(1L)
                .build();

        for (int index = 0; index < 10_100; index++) {
            cache.put("user-" + index + ":session-" + index, sessionData);
        }
        cache.cleanUp();

        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(10_000L);
    }

    @Test
    @DisplayName("활성 세션 조회 중 저장소 실패는 null로 반환된다")
    void getActiveSession_StoreFailure_ReturnsNull() {
        when(sessionStore.findByUserId(USER_ID)).thenThrow(new IllegalStateException("store down"));

        SessionData result = sessionService.getActiveSession(USER_ID);

        assertThat(result).isNull();
    }
}
