package com.github.brunopacheco1.praecepta.maven.plugin.rules;

import com.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import com.github.brunopacheco1.praecepta.engine.PraeceptaEngine;

public class PluginRulesEngine extends PraeceptaEngine<PraeceptaInput, PraeceptaOutput> {

    public PluginRulesEngine(HitPolicy hitPolicy) {
        super(hitPolicy, input -> input, output -> output);
    }
}
