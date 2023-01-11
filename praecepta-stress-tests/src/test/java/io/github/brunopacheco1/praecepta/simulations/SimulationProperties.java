package io.github.brunopacheco1.praecepta.simulations;

public class SimulationProperties {

    private SimulationProperties() {
        //Utility class
    }

    public static String baseUrl() {
        return System.getProperty("baseUrl", "http://localhost:8080");
    }

    public static Integer users() {
        return Integer.getInteger("users", 100);
    }

    public static Long durationInSecs() {
        return Long.getLong("durationInSecs", 60);
    }
}
