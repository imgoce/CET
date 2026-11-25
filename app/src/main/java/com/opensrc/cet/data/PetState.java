package com.opensrc.cet.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 펫 화면·보상 로직이 함께 읽는 펫 상태 (계약서 §08). 화면은 항상 DB가 돌려준 이 값으로 갱신한다. */
public final class PetState {
    public final long level;
    /** 현재 레벨에서 먹인 개수. level + 1 에 도달하면 레벨업 */
    public final long progress;
    public final long foodBalance;
    public final PetStage stage;
    /** 성공한 뽑기 횟수 */
    public final long drawCount;
    /** 다음 뽑기 비용 = 24 + drawCount */
    public final long nextDrawCost;
    /** 보유 장식 코드 ({@link Costumes}) */
    public final List<String> ownedCostumes;
    /** 착용 중인 장식 코드, 없으면 null */
    public final String equippedCostume;

    public PetState(long level, long progress, long foodBalance, PetStage stage,
                    long drawCount, long nextDrawCost,
                    List<String> ownedCostumes, String equippedCostume) {
        this.level = level;
        this.progress = progress;
        this.foodBalance = foodBalance;
        this.stage = Objects.requireNonNull(stage, "stage");
        this.drawCount = drawCount;
        this.nextDrawCost = nextDrawCost;
        this.ownedCostumes = Collections.unmodifiableList(new ArrayList<>(ownedCostumes));
        this.equippedCostume = equippedCostume;
    }
}
