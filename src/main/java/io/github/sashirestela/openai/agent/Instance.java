package io.github.sashirestela.openai.agent;

import io.github.sashirestela.openai.SimpleOpenAI;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Represents an OpenAI/Azure instance with its configuration and capabilities.
 * Each instance tracks which models are deployed and available.
 */
@Getter
@Builder
public class Instance {

    /**
     * Unique identifier for this instance (e.g., "openai-0", "azure-chat-0", "azure-dalle-0")
     */
    private final String id;

    /**
     * Base URL for this instance
     */
    private final String baseUrl;

    /**
     * API key for authentication
     */
    private final String apiKey;

    /**
     * Provider type (OpenAI or Azure)
     */
    private final Provider provider;

    /**
     * Azure API version (only used for Azure instances)
     * Example: "2024-08-01-preview" for chat, "2024-02-01" for DALL-E
     */
    private final String azureApiVersion;

    /**
     * List of model names deployed on this instance
     * Examples: ["gpt-4o", "gpt-4o-mini"], ["dall-e-3"], etc.
     */
    private final List<String> deployedModels;

    /**
     * The actual OpenAI client for making API calls
     */
    private final SimpleOpenAI client;

    /**
     * Check if this instance has a specific model deployed
     *
     * @param model Model name to check
     * @return true if this instance has the model
     */
    public boolean hasModel(String model) {
        return deployedModels != null && deployedModels.contains(model);
    }

    @Override
    public String toString() {
        return String.format("Instance{id='%s', provider=%s, models=%s}",
                id, provider, deployedModels);
    }
}
