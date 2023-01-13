package io.com.github.brunopacheco1.praecepta.benchmark.service;

import io.github.brunopacheco1.praecepta.loader.excel.ExcelLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;

import static io.com.github.brunopacheco1.praecepta.benchmark.dto.InputOutputConst.INPUTS;
import static io.com.github.brunopacheco1.praecepta.benchmark.dto.InputOutputConst.OUTPUTS;

@Service
public class BenchmarkExcelLoader extends ExcelLoader {

    public BenchmarkExcelLoader(
            @Value("${decision-table.path}") String excelPath
    ) {
        super(excelPath, new HashSet<>(INPUTS), new HashSet<>(OUTPUTS));
    }
}
