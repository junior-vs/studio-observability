package io.github.observa4j.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Main deployment processor for OBSERVA4J extension.
 * 
 * This class is responsible for build-time augmentation and configuration
 * of the unified observability features.
 */
public class Observa4jProcessor {

    private static final String FEATURE = "observa4j";

    /**
     * Register the OBSERVA4J feature.
     * This makes the extension visible in Quarkus tooling.
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    // Additional BuildSteps will be added here for:
    // - Automatic context injection
    // - Interceptor registration
    // - Health check discovery
    // - Metrics registration
    // - Exception reporter configuration
}
