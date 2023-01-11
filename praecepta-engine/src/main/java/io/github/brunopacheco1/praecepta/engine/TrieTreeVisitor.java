package io.github.brunopacheco1.praecepta.engine;

public interface TrieTreeVisitor {

    default void visit(TrieTreeNode node) {
        // do nothing
    }

    default void finish(TrieTreeNode node) {
        // do nothing
    }
}
