package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Interface for all agent result classes.
 * Provides a generic method for JSON deserialization.
 */
public interface AgentResult {

    ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Generic method to map JSON to an AgentResult implementation.
     *
     * @param <T>   The target AgentResult type
     * @param json  The JSON string to parse
     * @param clazz The target class
     * @return The deserialized instance
     * @throws RuntimeException if deserialization fails
     */
    static <T extends AgentResult> T jsonMapper(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Error mapping JSON to " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

}
