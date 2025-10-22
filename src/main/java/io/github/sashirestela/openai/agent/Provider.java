package io.github.sashirestela.openai.agent;

/**
 * Enum representing the API provider type for an instance.
 */
public enum Provider {
    /**
     * Standard OpenAI API (api.openai.com)
     * Typically has all models: gpt-4o, gpt-4o-mini, gpt-3.5-turbo, dall-e-3, etc.
     */
    OPENAI,

    /**
     * Azure OpenAI Service (*.openai.azure.com)
     * Models vary by deployment - can have chat models, vision models, or both
     */
    AZURE
}
