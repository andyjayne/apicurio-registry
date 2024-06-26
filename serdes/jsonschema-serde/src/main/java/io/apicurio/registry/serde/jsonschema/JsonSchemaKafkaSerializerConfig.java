package io.apicurio.registry.serde.jsonschema;

import io.apicurio.registry.serde.config.BaseKafkaSerDeConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;

import java.util.Map;

import static io.apicurio.registry.serde.SerdeConfig.*;

public class JsonSchemaKafkaSerializerConfig extends BaseKafkaSerDeConfig {

    private static ConfigDef configDef() {
        return new ConfigDef().define(VALIDATION_ENABLED, Type.BOOLEAN, VALIDATION_ENABLED_DEFAULT,
                Importance.MEDIUM, "Whether to validate the data against the json schema");
    }

    /**
     * Constructor.
     * 
     * @param originals
     */
    public JsonSchemaKafkaSerializerConfig(Map<?, ?> originals) {
        super(configDef(), originals);

    }

    public boolean validationEnabled() {
        return this.getBoolean(VALIDATION_ENABLED);
    }
}
