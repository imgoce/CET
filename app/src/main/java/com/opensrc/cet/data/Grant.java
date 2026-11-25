package com.opensrc.cet.data;

import java.util.Objects;

/** 먹이 지급 1건 (계약서 §14). */
public final class Grant {
    public final GrantReason reason;
    public final int food;

    public Grant(GrantReason reason, int food) {
        this.reason = Objects.requireNonNull(reason, "reason");
        this.food = food;
    }

    @Override
    public String toString() {
        return "Grant{" + reason + ", " + food + "}";
    }
}
