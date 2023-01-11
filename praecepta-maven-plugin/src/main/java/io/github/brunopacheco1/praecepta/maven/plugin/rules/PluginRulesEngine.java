package io.github.brunopacheco1.praecepta.maven.plugin.rules;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;

public class PluginRulesEngine extends PraeceptaEngine<PraeceptaInput, PraeceptaOutput> {

    public PluginRulesEngine(HitPolicy hitPolicy) {
        super(hitPolicy, input -> input, output -> output);
    }
}
