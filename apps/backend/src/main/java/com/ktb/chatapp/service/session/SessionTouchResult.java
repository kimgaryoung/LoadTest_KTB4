package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;

public record SessionTouchResult(Status status, Session session) {

    public enum Status {
        VALID,
        NOT_FOUND,
        SESSION_ID_MISMATCH,
        EXPIRED
    }

    public static SessionTouchResult valid(Session session) {
        return new SessionTouchResult(Status.VALID, session);
    }

    public static SessionTouchResult invalid(Status status) {
        return new SessionTouchResult(status, null);
    }
}
