package io.github.brunopacheco1.praecepta.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.beans.FileRow;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFieldException;

public abstract class BaseLoader implements PraeceptaLoader {
 
    private final Set<String> inputs;
    private final Set<String> outputs;

    protected BaseLoader(Set<String> inputs, Set<String> outputs) {
        this.inputs = inputs;
        this.outputs = outputs;
    }

    protected void validateFields(Map<Integer, String> positioning) {
        var missingFields = Stream.concat(inputs.stream(), outputs.stream())
                .filter(Predicate.not(positioning::containsValue))
                .collect(Collectors.toSet());

        if (!missingFields.isEmpty()) {
            throw new MissingFieldException(missingFields);
        }
    }

    /**
     * Parses the records into INPUT/OUTPUT objects and prepares the collection of praecepta.
     *
     * @param records the rows extracted from the Excel file.
     */
    protected List<Praeceptum> parseRowsToPraecepta(Iterable<FileRow> records) {
        var praecepta = new ArrayList<Praeceptum>();
        for (FileRow row : records) {
            var input = parseInput(row);
            var output = parseOutput(row);
            praecepta.add(new Praeceptum(row.getRowNumber(), input, output));
        }
        return praecepta;
    }

    /**
     * Method responsible to inject values into RuleInput
     *
     * @param row Extracted Excel row
     * @return returns the fulfilled instance.
     */
    private PraeceptaInput parseInput(FileRow row) {
        return new PraeceptaInput(row.get(inputs));
    }

    /**
     * Method responsible to inject values into RuleOutput
     *
     * @param row Extracted Excel row
     * @return returns the fulfilled instance.
     */
    private PraeceptaOutput parseOutput(FileRow row) {
        return new PraeceptaOutput(row.get(outputs));
    }
}
