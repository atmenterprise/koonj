package uk.appsthatmatter.koonj.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class McpArguments {
    private static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private final JsonNode values;

    record Field(String name, boolean required, Map<String, Object> schema) {
        static Field uuid(String name, boolean required) {
            return new Field(name, required, Map.of("type", "string", "format", "uuid"));
        }

        static Field text(String name, boolean required, int limit) {
            return new Field(name, required, Map.of("type", "string", "minLength", 1, "maxLength", limit));
        }

        static Field integer(String name, int min, int max, int defaultValue) {
            return new Field(name, false, Map.of("type", "integer", "minimum", min, "maximum", max, "default", defaultValue));
        }

        static Field choices(String name, String... options) {
            return new Field(name, false, Map.of("type", "string", "enum", List.of(options)));
        }

        static Field array(Field entry) {
            return new Field(entry.name(), false, Map.of("type", "array", "items", entry.schema(), "minItems", 1, "maxItems", 100));
        }

        Field requiredField() { return new Field(name, true, schema); }
    }

    McpArguments(JsonNode values, List<Field> fields) {
        this.values = values;
        if (values.isMissingNode()) {
            if (fields.stream().anyMatch(Field::required)) throw new IllegalArgumentException("Required tool arguments are missing");
            return;
        }
        if (!values.isObject()) throw new IllegalArgumentException("Tool arguments must be an object");
        for (Field field : fields) {
            JsonNode value = values.path(field.name());
            if (value.isMissingNode() && !field.required()) continue;
            validate(field.name(), field.schema(), value);
        }
        var names = values.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (fields.stream().noneMatch(field -> field.name().equals(name))) {
                throw new IllegalArgumentException("Unexpected tool argument");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void validate(String name, Map<String, Object> schema, JsonNode value) {
        boolean valid = switch ((String) schema.get("type")) {
            case "string" -> {
                if (!value.isTextual() || value.textValue().isBlank() || value.textValue().indexOf('\0') >= 0) yield false;
                if (schema.containsKey("format")) yield UUID_FORMAT.matcher(value.textValue()).matches();
                if (schema.get("enum") instanceof List<?> options) yield options.contains(value.textValue());
                yield value.textValue().length() <= (int) schema.get("maxLength");
            }
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= (int) schema.get("minimum") && value.intValue() <= (int) schema.get("maximum");
            case "array" -> {
                if (!value.isArray() || value.size() < (int) schema.get("minItems") || value.size() > (int) schema.get("maxItems")) yield false;
                for (JsonNode child : value) validate(name, (Map<String, Object>) schema.get("items"), child);
                yield true;
            }
            default -> throw new IllegalStateException("Invalid tool schema");
        };
        if (!valid) throw new IllegalArgumentException("Invalid or missing argument [" + name + "].");
    }

    static Map<String, Object> schema(List<Field> fields) {
        var properties = new LinkedHashMap<String, Object>();
        for (Field field : fields) properties.put(field.name(), field.schema());
        return Map.of("type", "object", "properties", properties, "additionalProperties", false,
                "required", fields.stream().filter(Field::required).map(Field::name).toList());
    }

    String text(String name) { return values.path(name).textValue(); }

    UUID uuid(String name) {
        String value = text(name);
        return value == null ? null : UUID.fromString(value);
    }

    int integer(String name, int defaultValue) { return values.path(name).asInt(defaultValue); }

    List<String> strings(String name) {
        var items = new ArrayList<String>();
        for (var item : values.path(name)) items.add(item.textValue());
        return items;
    }
}
