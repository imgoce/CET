package com.opensrc.cet.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** getShadowSites 결과 (계약서 §07). 측정 수 오름차순·센서 번호순 5곳. 초기 updatedAt 은 null 가능. */
public final class ShadowSites {
    public final List<ShadowSite> sites;
    public final Instant updatedAt;

    public ShadowSites(List<ShadowSite> sites, Instant updatedAt) {
        this.sites = Collections.unmodifiableList(new ArrayList<>(sites));
        this.updatedAt = updatedAt;
    }
}
