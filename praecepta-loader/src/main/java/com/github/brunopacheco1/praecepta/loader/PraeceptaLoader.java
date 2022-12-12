package com.github.brunopacheco1.praecepta.loader;

import java.util.List;

import com.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

public interface PraeceptaLoader {


    /**
     * It returns a collection of praecepta, built during the loading process.
     *
     * @return collection of praecepta
     */
    List<Praeceptum> getPraecepta();
}
