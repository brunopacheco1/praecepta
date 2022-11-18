package com.github.brunopacheco1.praecepta;

import java.util.List;

public final record InputString(Integer ruleId, List<String> values) {

    public String get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }
}
