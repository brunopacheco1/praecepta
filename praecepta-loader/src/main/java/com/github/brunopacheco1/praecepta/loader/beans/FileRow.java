package com.github.brunopacheco1.praecepta.loader.beans;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.brunopacheco1.praecepta.loader.exceptions.ParsingInputOutputException;

public class FileRow {

    private final Map<String, String> values = new HashMap<>();

    private final Integer rowNumber;

    public FileRow(Integer rowNumber) {
        this.rowNumber = rowNumber;
    }

    public String get(String key) {
        return values.get(key);
    }

    public void set(String key, String value) {
        if (value != null && !value.isEmpty()) {
            values.put(key, value);
        } else {
            values.put(key, null);
        }
    }

    public Map<String, String> get(Set<String> keys) {
        var filteredEntries = values.entrySet().stream()
                .filter(it -> keys.contains(it.getKey()))
                .collect(Collectors.toSet());

        if(filteredEntries.size() != keys.size()) {
            throw new ParsingInputOutputException();
        }

        var result = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : filteredEntries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public Integer getRowNumber() {
        return rowNumber;
    }

    public boolean isNotEmpty() {
        return values.values().stream().anyMatch(Objects::nonNull);
    }
}
