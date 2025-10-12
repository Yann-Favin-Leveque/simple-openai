package io.github.sashirestela.openai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sashirestela.openai.common.ResponseFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates JSON Schema from Java classes for OpenAI structured outputs.
 */
public class JsonSchemaGenerator {

    private static final Logger logger = LoggerFactory.getLogger(JsonSchemaGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a ResponseFormat with JSON Schema from a result class name.
     *
     * @param resultClassName Name of the result class (without package)
     * @param packageName     Package name where the class is located
     * @return ResponseFormat with JSON Schema or fallback format
     */
    public static ResponseFormat createResponseFormat(String resultClassName, String packageName) {
        try {
            Class<?> clazz = Class.forName(packageName + "." + resultClassName);
            ObjectNode schema = buildSchemaForClass(clazz, new HashSet<>());

            ResponseFormat.JsonSchema jsonSchema = ResponseFormat.JsonSchema.builder()
                    .name(resultClassName.toLowerCase() + "_format")
                    .schema(schema)
                    .strict(true)
                    .build();

            logger.debug("Successfully created JSON Schema for class: {}", resultClassName);
            return ResponseFormat.jsonSchema(jsonSchema);

        } catch (Exception e) {
            logger.warn("Failed to create JSON Schema for class: {}", resultClassName, e);
            return ResponseFormat.JSON_OBJECT;
        }
    }

    private static ObjectNode buildSchemaForClass(Class<?> clazz, Set<Class<?>> visitedClasses) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        if (visitedClasses.contains(clazz)) {
            logger.debug("Circular reference detected for class: {}", clazz.getSimpleName());
            return schema;
        }
        visitedClasses.add(clazz);

        ObjectNode properties = mapper.createObjectNode();
        List<String> required = new ArrayList<>();

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                if (field.getType() == Object.class) {
                    continue;
                }

                String fieldName = field.getName();
                ObjectNode fieldSchema = buildSchemaForField(field, visitedClasses);
                properties.set(fieldName, fieldSchema);

                if (!java.util.Map.class.isAssignableFrom(field.getType())) {
                    required.add(fieldName);
                }
            }
        }

        schema.set("properties", properties);
        schema.set("required", mapper.valueToTree(required));
        visitedClasses.remove(clazz);

        return schema;
    }

    private static ObjectNode buildSchemaForField(Field field, Set<Class<?>> visitedClasses) {
        ObjectNode fieldSchema = mapper.createObjectNode();
        Class<?> fieldType = field.getType();

        if (fieldType == String.class) {
            fieldSchema.put("type", "string");
        } else if (fieldType == Integer.class || fieldType == int.class) {
            fieldSchema.put("type", "integer");
        } else if (fieldType == Long.class || fieldType == long.class) {
            fieldSchema.put("type", "integer");
        } else if (fieldType == Double.class || fieldType == double.class ||
                fieldType == Float.class || fieldType == float.class) {
            fieldSchema.put("type", "number");
        } else if (fieldType == Boolean.class || fieldType == boolean.class) {
            fieldSchema.put("type", "boolean");
        } else if (java.util.Map.class.isAssignableFrom(fieldType)) {
            fieldSchema.put("type", "object");
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                ParameterizedType paramType = (ParameterizedType) genericType;
                Type[] actualTypes = paramType.getActualTypeArguments();
                if (actualTypes.length > 1 && actualTypes[1] instanceof Class<?>) {
                    Class<?> valueType = (Class<?>) actualTypes[1];
                    ObjectNode additionalProps = mapper.createObjectNode();

                    if (valueType == String.class) {
                        additionalProps.put("type", "string");
                    } else if (valueType == Integer.class || valueType == int.class) {
                        additionalProps.put("type", "integer");
                    } else if (valueType == Boolean.class || valueType == boolean.class) {
                        additionalProps.put("type", "boolean");
                    } else {
                        additionalProps = buildSchemaForClass(valueType, new HashSet<>(visitedClasses));
                    }

                    fieldSchema.set("additionalProperties", additionalProps);
                } else {
                    ObjectNode additionalProps = mapper.createObjectNode();
                    additionalProps.put("type", "string");
                    fieldSchema.set("additionalProperties", additionalProps);
                }
            } else {
                ObjectNode additionalProps = mapper.createObjectNode();
                additionalProps.put("type", "string");
                fieldSchema.set("additionalProperties", additionalProps);
            }
        } else if (List.class.isAssignableFrom(fieldType)) {
            fieldSchema.put("type", "array");
            Type genericType = field.getGenericType();
            ObjectNode items = mapper.createObjectNode();

            if (genericType instanceof ParameterizedType) {
                ParameterizedType paramType = (ParameterizedType) genericType;
                Type[] actualTypes = paramType.getActualTypeArguments();
                if (actualTypes.length > 0 && actualTypes[0] instanceof Class<?>) {
                    Class<?> itemType = (Class<?>) actualTypes[0];
                    if (itemType == String.class) {
                        items.put("type", "string");
                    } else if (itemType.getDeclaringClass() != null || isCustomClass(itemType)) {
                        items = buildSchemaForClass(itemType, new HashSet<>(visitedClasses));
                    } else {
                        items.put("type", "object");
                        items.put("additionalProperties", false);
                    }
                } else {
                    items.put("type", "object");
                    items.put("additionalProperties", false);
                }
            } else {
                items.put("type", "object");
                items.put("additionalProperties", false);
            }

            fieldSchema.set("items", items);
        } else if (fieldType == Object.class) {
            fieldSchema.put("type", "null");
        } else if (fieldType.getDeclaringClass() != null) {
            return buildSchemaForClass(fieldType, visitedClasses);
        } else {
            fieldSchema.put("type", "object");
            fieldSchema.put("additionalProperties", false);
        }

        fieldSchema.put("description", "Field " + field.getName() + " of type " + fieldType.getSimpleName());
        return fieldSchema;
    }

    private static boolean isCustomClass(Class<?> clazz) {
        return clazz.getPackage() != null &&
                !clazz.getPackage().getName().startsWith("java.");
    }

    public static boolean canGenerateSchema(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return clazz.getDeclaredFields().length > 0;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}
