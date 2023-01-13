package io.com.github.brunopacheco1.praecepta.camunda.benchmark;

import io.com.github.brunopacheco1.praecepta.camunda.benchmark.dto.InputOutputConst;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.DmnModelInstance;
import org.camunda.bpm.model.dmn.HitPolicy;
import org.camunda.bpm.model.dmn.instance.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CsvToDmn {

    public static void main(String[] args) throws IOException {

        // Read the CSV file
        InputStream inputStream = CsvToDmn.class.getClassLoader().getResourceAsStream("decision_table.csv");
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        CSVParser csvParser = new CSVParser(inputStreamReader, CSVFormat.newFormat(';'));
        List<CSVRecord> csvRecords = csvParser.getRecords();

        // Create a new DMN model
        DmnModelInstance modelInstance = Dmn.createEmptyModel();

        // Create a new decision table
        Decision decision = modelInstance.newInstance(Decision.class);
        decision.setId("decision_table");
        decision.setName("decision_table");
        Definitions definitions = modelInstance.newInstance(Definitions.class);
        definitions.setName("DRD");
        definitions.setNamespace("http://www.omg.org/spec/DMN/20180521/MODEL/");
        modelInstance.setDefinitions(definitions);
        modelInstance.getDefinitions().addChildElement(decision);
        DecisionTable decisionTable = modelInstance.newInstance(DecisionTable.class);
        decisionTable.setHitPolicy(HitPolicy.UNIQUE);
        decision.addChildElement(decisionTable);

        // Add inputs to the decision table
        for (String inputLabel : InputOutputConst.INPUTS) {
            Input input = modelInstance.newInstance(Input.class);
            input.setId(inputLabel);
            input.setLabel(inputLabel);
            InputExpression inputExpression = modelInstance.newInstance(InputExpression.class);
            inputExpression.setId("expression_%s".formatted(inputLabel));
            inputExpression.setLabel(inputLabel);
            Text text = modelInstance.newInstance(Text.class);
            text.setTextContent(inputLabel);
            inputExpression.setText(text);
            input.setInputExpression(inputExpression);
            decisionTable.getInputs().add(input);
        }

        // Add outputs to the decision table
        for (String outputLabel : InputOutputConst.OUTPUTS) {
            Output output = modelInstance.newInstance(Output.class);
            output.setId(outputLabel);
            output.setLabel(outputLabel);
            decisionTable.getOutputs().add(output);
        }

        // Add rules to the decision table
        for (int i = 1; i < csvRecords.size(); i++) {
            CSVRecord csvRecord = csvRecords.get(i);
            Rule rule = modelInstance.newInstance(Rule.class);
            decisionTable.getRules().add(rule);

            // Add input entries to the rule
            for (int j = 0; j < InputOutputConst.INPUTS.size(); j++) {
                InputEntry inputEntry = modelInstance.newInstance(InputEntry.class);
                inputEntry.setId("input_%s_%s".formatted(i, j));
                Text text = modelInstance.newInstance(Text.class);
                text.setTextContent(toDmnList(csvRecord.get(j)));
                inputEntry.setText(text);
                rule.getInputEntries().add(inputEntry);
            }
            // Add output entries to the rule
            for (int j = 0; j < InputOutputConst.OUTPUTS.size(); j++) {
                OutputEntry outputEntry = modelInstance.newInstance(OutputEntry.class);
                outputEntry.setId("output_%s_%s".formatted(i, InputOutputConst.INPUTS.size() + j));
                Text text = modelInstance.newInstance(Text.class);
                text.setTextContent(toDmnString(csvRecord.get(InputOutputConst.INPUTS.size() + j)));
                outputEntry.setText(text);
                rule.getOutputEntries().add(outputEntry);
            }
        }

        Dmn.writeModelToFile(new File("decision_table.dmn"), modelInstance);
    }

    private static String toDmnList(String value) {
        return value.isEmpty() ? value : Stream.of(value.split(","))
                .map(CsvToDmn::toDmnString)
                .collect(Collectors.joining(","));
    }

    private static String toDmnString(String value) {
        return value.isEmpty() ? value : "\"%s\"".formatted(value);
    }
}
