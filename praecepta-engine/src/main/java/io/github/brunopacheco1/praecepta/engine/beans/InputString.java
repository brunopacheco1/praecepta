package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.List;

public final record InputString(Integer ruleId, List<String> values) {

    public String get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }
}
