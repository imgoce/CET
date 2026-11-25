package com.opensrc.cet.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** getAnalysisInputs 결과 (계약서 §12). 조회일 포함 최근 KST 14일의 운영 데이터. */
public final class AnalysisInputs {
    public final List<Sample> samples;
    public final List<FeedbackRow> feedbacks;

    public AnalysisInputs(List<Sample> samples, List<FeedbackRow> feedbacks) {
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        this.feedbacks = Collections.unmodifiableList(new ArrayList<>(feedbacks));
    }
}
