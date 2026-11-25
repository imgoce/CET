package com.opensrc.cet.db;

import java.util.Objects;

/**
 * 팀 DB 호출의 공통 결과 (계약서 §02).
 * 성공: ok=true, data 있음 / 실패: ok=false, error 있음.
 *
 * <pre>
 * db.feedPet(userId, requestId, result -> {
 *     if (result.ok) {
 *         showPet(result.data.pet);
 *     } else if (result.error.code == TeamDbErrorCode.INSUFFICIENT_FOOD) {
 *         disableFeedButton();
 *     }
 * });
 * </pre>
 */
public final class TeamDbResult<T> {
    public final boolean ok;
    public final T data;
    public final TeamDbError error;

    private TeamDbResult(boolean ok, T data, TeamDbError error) {
        this.ok = ok;
        this.data = data;
        this.error = error;
    }

    public static <T> TeamDbResult<T> success(T data) {
        return new TeamDbResult<>(true, Objects.requireNonNull(data, "data"), null);
    }

    public static <T> TeamDbResult<T> failure(TeamDbError error) {
        return new TeamDbResult<>(false, null, Objects.requireNonNull(error, "error"));
    }

    public static <T> TeamDbResult<T> failure(TeamDbErrorCode code, String message, boolean retryable) {
        return failure(new TeamDbError(code, message, retryable));
    }
}
