package com.opensrc.cet.data;

import java.util.Objects;

/** 랭킹 1줄 (계약서 §11). 레벨·진행도가 같으면 같은 순위(1, 2, 2, 4). 사용자 UUID 는 없다. */
public final class RankRow {
    public final int rank;
    public final String nickname;
    public final long level;
    public final long progress;
    public final PetStage stage;

    public RankRow(int rank, String nickname, long level, long progress, PetStage stage) {
        this.rank = rank;
        this.nickname = Objects.requireNonNull(nickname, "nickname");
        this.level = level;
        this.progress = progress;
        this.stage = Objects.requireNonNull(stage, "stage");
    }
}
