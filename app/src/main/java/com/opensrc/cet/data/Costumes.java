package com.opensrc.cet.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 장식 코드 (계약서 §11). 머리장식 4종, 한 번에 1개 착용. */
public final class Costumes {
    public static final String SPROUT = "SPROUT";   // 새싹
    public static final String RIBBON = "RIBBON";   // 리본
    public static final String STAR = "STAR";       // 별
    public static final String CROWN = "CROWN";     // 왕관

    public static final List<String> ALL =
            Collections.unmodifiableList(Arrays.asList(SPROUT, RIBBON, STAR, CROWN));

    private Costumes() {}
}
