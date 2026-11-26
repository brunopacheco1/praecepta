/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.Decision;

/**
 * Finds the cycles of required decisions, which DMN forbids (DMN 1.5, 6.3.7), and orders the other decisions, each
 * after the decisions it requires. It reads only which decisions each decision requires, and changes nothing: what it
 * finds is in its fields. Every walk is a loop with a stack or a queue of its own, so a long chain of decisions takes
 * no more of the thread's stack than a short one.
 */
final class GraphCycles {

    /** A cycle: its decisions, by index, and what the validation report says of it. */
    record Cycle(List<Integer> members, String detail) {
    }

    /** The cycles: every strongly connected component of more than one decision, or of one that requires itself. */
    final List<Cycle> found = new ArrayList<>();
    /** By decision: whether it is in a cycle. */
    final boolean[] cyclic;
    /** The decisions in no cycle and requiring none in one, by index, each after the decisions it requires. */
    final int[] order;

    private final int[][] required;
    private final List<Decision> decisions;

    /**
     * @param required  by decision, the indexes of the decisions it requires
     * @param decisions the model's decisions, which the cycles' details name
     */
    GraphCycles(int[][] required, List<Decision> decisions) {
        this.required = required;
        this.decisions = decisions;
        cyclic = new boolean[required.length];
        var components = new StronglyConnected(required);
        for (int root = 0; root < required.length; root++) {
            components.visit(root).forEach(this::addIfCycle);
        }
        order = topologicalOrder();
    }

    /**
     * Tarjan's algorithm for the strongly connected components of the graph of required decisions, with a stack of
     * frames of its own instead of recursion.
     */
    private static final class StronglyConnected {
        private final int[][] edges;
        private final int[] index;
        private final int[] low;
        private final boolean[] onStack;
        private final ArrayDeque<Integer> stack = new ArrayDeque<>();
        private int next;

        StronglyConnected(int[][] edges) {
            this.edges = edges;
            index = new int[edges.length];
            low = new int[edges.length];
            onStack = new boolean[edges.length];
            Arrays.fill(index, -1);
        }

        /** The components found from {@code root}, if it was not visited yet; each is complete when it is listed. */
        List<List<Integer>> visit(int root) {
            var components = new ArrayList<List<Integer>>();
            if (index[root] >= 0) {
                return components;
            }
            // each frame: a decision and how many of the decisions it requires it has visited
            var frames = new ArrayDeque<int[]>();
            enter(root, frames);
            while (!frames.isEmpty()) {
                var frame = frames.peek();
                int d = frame[0];
                var required = edges[d];
                if (frame[1] < required.length) {
                    follow(d, required[frame[1]++], frames);
                } else {
                    frames.pop();
                    if (!frames.isEmpty()) {
                        int parent = frames.peek()[0];
                        low[parent] = Math.min(low[parent], low[d]);
                    }
                    if (low[d] == index[d]) {
                        components.add(component(d));
                    }
                }
            }
            return components;
        }

        private void enter(int d, ArrayDeque<int[]> frames) {
            index[d] = next;
            low[d] = next;
            next++;
            stack.push(d);
            onStack[d] = true;
            frames.push(new int[] {d, 0});
        }

        private void follow(int d, int required, ArrayDeque<int[]> frames) {
            if (index[required] < 0) {
                enter(required, frames);
            } else if (onStack[required]) {
                low[d] = Math.min(low[d], index[required]);
            }
        }

        /** The component whose root is {@code d}: the decisions on the stack down to it. */
        private List<Integer> component(int d) {
            var component = new ArrayList<Integer>();
            int member;
            do {
                member = stack.pop();
                onStack[member] = false;
                component.add(member);
            } while (member != d);
            return component;
        }
    }

    /** Keeps a strongly connected component that is a cycle, with what the validation report says of it. */
    private void addIfCycle(List<Integer> component) {
        int first = component.stream().mapToInt(Integer::intValue).min().orElseThrow();
        boolean selfRequiring = Arrays.stream(required[first]).anyMatch(r -> r == first);
        if (component.size() == 1 && !selfRequiring) {
            return;
        }
        component.forEach(member -> cyclic[member] = true);
        String detail;
        if (component.size() == 1) {
            detail = "decision '%s' requires itself (DMN 1.5, 6.3.7: a decision SHALL not require itself, directly or indirectly)"
                    .formatted(decisions.get(first).name());
        } else {
            var names = cycleThrough(first, Set.copyOf(component)).stream()
                    .map(d -> "'" + decisions.get(d).name() + "'")
                    .collect(Collectors.joining(" -> "));
            detail = "decisions %s require one another in a cycle (DMN 1.5, 6.3.7: a decision SHALL not require itself, directly or indirectly)"
                    .formatted(names);
        }
        found.add(new Cycle(component, detail));
    }

    /** A cycle from {@code start} back to it within a strongly connected component, found breadth first: start, ..., start. */
    private List<Integer> cycleThrough(int start, Set<Integer> component) {
        var previous = new HashMap<Integer, Integer>();
        var queue = new ArrayDeque<Integer>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int d = queue.poll();
            for (int r : required[d]) {
                if (r == start) {
                    var cycle = new ArrayList<Integer>();
                    for (int at = d; at != start; at = previous.get(at)) {
                        cycle.add(at);
                    }
                    cycle.add(start);
                    Collections.reverse(cycle); // start, ..., d, each requiring the next
                    cycle.add(start);
                    return cycle;
                }
                if (component.contains(r) && previous.putIfAbsent(r, d) == null) {
                    queue.add(r);
                }
            }
        }
        throw new IllegalStateException("no cycle through a decision of a strongly connected component");
    }

    /** The decisions in no cycle and requiring none in one, each after the decisions it requires (Kahn's algorithm). */
    private int[] topologicalOrder() {
        int count = required.length;
        var pending = new int[count];
        var dependents = new ArrayList<List<Integer>>();
        for (int d = 0; d < count; d++) {
            dependents.add(new ArrayList<>());
        }
        for (int d = 0; d < count; d++) {
            pending[d] = required[d].length;
            for (int r : required[d]) {
                dependents.get(r).add(d);
            }
        }
        var ready = new ArrayDeque<Integer>();
        for (int d = 0; d < count; d++) {
            if (pending[d] == 0 && !cyclic[d]) {
                ready.add(d);
            }
        }
        var ordered = new ArrayList<Integer>();
        while (!ready.isEmpty()) {
            int d = ready.poll();
            ordered.add(d);
            for (int dependent : dependents.get(d)) {
                if (--pending[dependent] == 0 && !cyclic[dependent]) {
                    ready.add(dependent);
                }
            }
        }
        return ordered.stream().mapToInt(Integer::intValue).toArray();
    }
}
