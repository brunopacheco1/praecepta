package io.github.brunopacheco1.praecepta.maven.plugin.templates;

import freemarker.template.*;
import java.util.*;

public class TemplateConfigurationConst {

    public static final Configuration CONFIGURATION;

    static {
        CONFIGURATION = new Configuration(new Version(2, 3, 31));

        // Where do we load the templates from:
        CONFIGURATION.setClassLoaderForTemplateLoading(TemplateConfigurationConst.class.getClassLoader(), "templates");

        // Some other recommended settings:
        CONFIGURATION.setDefaultEncoding("UTF-8");
        CONFIGURATION.setLocale(Locale.US);
        CONFIGURATION.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    }
}
