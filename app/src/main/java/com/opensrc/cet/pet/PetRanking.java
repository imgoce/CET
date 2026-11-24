package com.opensrc.cet.pet;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class PetRanking {
    private static final int TOP_LIMIT = 50;

    private PetRanking() {}

    public static Result calculate(Collection<Entry> users, String userId) {
        List<Entry> sorted = new ArrayList<>(users);
        sorted.sort(PetRanking::compareEntries);

        List<Row> top = new ArrayList<>(Math.min(sorted.size(), TOP_LIMIT));
        Row me = null;
        Entry previous = null;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Entry entry = sorted.get(i);
            if (previous == null || entry.level != previous.level || entry.progress != previous.progress) {
                rank = i + 1;
            }
            boolean mine = entry.userId.equals(userId);
            if (i < TOP_LIMIT || mine) {
                Row row = new Row(rank, entry);
                if (i < TOP_LIMIT) top.add(row);
                if (mine) me = row;
            }
            previous = entry;
        }
        if (me == null) throw new NoSuchElementException("userId");
        return new Result(top, me);
    }

    private static int compareEntries(Entry left, Entry right) {
        int byLevel = Long.compare(right.level, left.level);
        if (byLevel != 0) return byLevel;
        int byProgress = Long.compare(right.progress, left.progress);
        if (byProgress != 0) return byProgress;
        return compareNicknames(left.nickname, right.nickname);
    }

    private static int compareNicknames(String left, String right) {
        // 보충 문자를 포함한 NFC 닉네임의 코드포인트 순서
        int offset = 0;
        while (offset < left.length() && offset < right.length()) {
            int a = left.codePointAt(offset);
            int b = right.codePointAt(offset);
            if (a != b) return Integer.compare(a, b);
            offset += Character.charCount(a);
        }
        return Integer.compare(left.length(), right.length());
    }

    public static final class Entry {
        private final String userId;
        private final String nickname;
        private final long level;
        private final long progress;

        public Entry(String userId, String nickname, long level, long progress) {
            if (level < 1 || progress < 0 || progress > level) {
                throw new IllegalArgumentException("level, progress");
            }
            this.userId = Objects.requireNonNull(userId, "userId");
            this.nickname = Normalizer.normalize(nickname, Normalizer.Form.NFC);
            this.level = level;
            this.progress = progress;
        }
    }

    public static final class Row {
        public final int rank;
        public final String nickname;
        public final long level;
        public final long progress;
        public final PetGrowth.Stage stage;

        private Row(int rank, Entry entry) {
            this.rank = rank;
            this.nickname = entry.nickname;
            this.level = entry.level;
            this.progress = entry.progress;
            this.stage = PetGrowth.stageFor(level);
        }
    }

    public static final class Result {
        public final List<Row> top;
        public final Row me;

        private Result(List<Row> top, Row me) {
            this.top = Collections.unmodifiableList(top);
            this.me = me;
        }
    }
}
