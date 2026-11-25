package com.opensrc.cet.db;

import java.util.Objects;

/** 팀 DB 호출 실패 정보 (계약서 §02). retryable=true 면 같은 요청 ID로 다시 보내도 된다. */
public final class TeamDbError {
    public final TeamDbErrorCode code;
    public final String message;
    public final boolean retryable;

    public TeamDbError(TeamDbErrorCode code, String message, boolean retryable) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = message == null ? "" : message;
        this.retryable = retryable;
    }

    @Override
    public String toString() {
        return "TeamDbError{" + code + ", retryable=" + retryable + ", " + message + "}";
    }
}
