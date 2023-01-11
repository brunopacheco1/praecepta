package io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.TrieTreeVisitor;
import io.github.brunopacheco1.praecepta.engine.TrieTreeNode;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans.NodeClassModel;

import static io.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;

public class NodeClassGenerator implements TrieTreeVisitor {

    private final Deque<String> nodeNameQueue = new ArrayDeque<>();
    private final String outputPath;
    private final String outputPackage;
    private final List<String> inputs;

    public NodeClassGenerator(
        String outputPath,
        String outputPackage,
        List<String> inputs
    ) {
        this.outputPath = outputPath;
        this.outputPackage = outputPackage;
        this.inputs = inputs;
    }

    @Override
    public void visit(TrieTreeNode node) {
        try {
            var currentValueName = node.getValue().toLowerCase().replaceAll("\\.", "_");
            var nodeName = (nodeNameQueue.peek() != null ? nodeNameQueue.peek() : "") + currentValueName;
            nodeNameQueue.push(nodeName);

            var input = node.getNodeLevel() != inputs.size() ? inputs.get(node.getNodeLevel()) : null;

            var model = new NodeClassModel(
                    outputPackage,
                    nodeName,
                    input,
                    node.getChildren().keySet().stream().filter(it -> !it.equals(TrieTreeNode.ANY)).collect(Collectors.toSet()),
                    node.getRuleIds(),
                    node.getChildren().containsKey(TrieTreeNode.ANY));

            var template = CONFIGURATION.getTemplate("compilation/node.ftl");

            generate(model, template, outputPath + File.separator + "Node" + nodeName + ".java");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void generate(Object templateModel, Template template, String outputFile) throws TemplateException, IOException {
        var file = new File(outputFile);
        var writer = new FileWriter(file);
        template.process(templateModel, writer);
        writer.close();
    }

    @Override
    public void finish(TrieTreeNode node) {
        nodeNameQueue.pop();
    }
}
