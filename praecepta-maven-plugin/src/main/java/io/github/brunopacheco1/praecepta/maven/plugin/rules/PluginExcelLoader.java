package io.github.brunopacheco1.praecepta.maven.plugin.rules;

import java.util.Set;

import io.github.brunopacheco1.praecepta.loader.excel.ExcelLoader;

public class PluginExcelLoader extends ExcelLoader {

    public PluginExcelLoader(String excelPath, Set<String> inputs, Set<String> outputs) {
        super(excelPath, inputs, outputs);
    }
}
