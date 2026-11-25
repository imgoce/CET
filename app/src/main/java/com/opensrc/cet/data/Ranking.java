package com.opensrc.cet.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** getRanking 결과 (계약서 §11). top 은 최대 50명, me 는 50위 밖이어도 항상 있다. */
public final class Ranking {
    public final List<RankRow> top;
    public final RankRow me;

    public Ranking(List<RankRow> top, RankRow me) {
        this.top = Collections.unmodifiableList(new ArrayList<>(top));
        this.me = Objects.requireNonNull(me, "me");
    }
}
