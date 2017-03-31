package com.github.epheatt.kafka.connect.morphlines;

import io.confluent.connect.avro.AvroData;
import io.confluent.connect.avro.AvroDataConfig;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.storage.Converter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

import org.kitesdk.morphline.api.Command;
import org.kitesdk.morphline.api.CommandBuilder;
import org.kitesdk.morphline.api.MorphlineCompilationException;
import org.kitesdk.morphline.api.MorphlineContext;
import org.kitesdk.morphline.api.Record;
import org.kitesdk.morphline.base.AbstractCommand;
import org.kitesdk.morphline.base.Configs;
import org.kitesdk.morphline.base.Fields;
import org.kitesdk.morphline.stdio.AbstractParser;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class ToConnectDataBuilder implements CommandBuilder {

    @Override
    public Collection<String> getNames() {
        return Collections.singletonList("toConnectData");
    }

    @Override
    public Command build(Config config, Command parent, Command child, MorphlineContext context) {
        return new ToConnectData(this, config, parent, child, context);
    }

    // /////////////////////////////////////////////////////////////////////////////
    // Nested classes:
    // /////////////////////////////////////////////////////////////////////////////
    /** Implementation that does logging and metrics */
    private static final class ToConnectData extends AbstractCommand {
        private final Map<String, String> mappings = new HashMap<String, String>();
        private final String topicField;
        private final String schemaField;
        private final String valueField;
        private final String converterType;
        private final Charset characterSet;
        private static final Converter JSON_CONVERTER;
        static {
            JSON_CONVERTER = new JsonConverter();
            JSON_CONVERTER.configure(Collections.singletonMap("schemas.enable", "false"), false);
        }
        private static final AvroData AVRO_CONVERTER;
        static {
            AVRO_CONVERTER = new AvroData(new AvroDataConfig.Builder()
                                            //.with(AvroDataConfig.SCHEMAS_CACHE_SIZE_CONFIG, cacheSize)
                                            .build());
        }
        
        public ToConnectData(CommandBuilder builder, Config config, Command parent, Command child, MorphlineContext context) {
            super(builder, config, parent, child, context);

            this.topicField = getConfigs().getString(config, "topicField", "topic");
            this.schemaField = getConfigs().getString(config, "schemaField", null);
            
            int numDefinitions = 0;
            if (schemaField != null) {
              numDefinitions++;
            }
            if (numDefinitions == 0) {
              throw new MorphlineCompilationException(
                "Either schemaFile or schemaString or schemaField must be defined", config);
            }
            
            this.valueField = getConfigs().getString(config, "valueField", "value");
            this.converterType = getConfigs().getString(config, "converter", "avro");
            this.characterSet = Charset.forName(getConfigs().getString(config, "characterSet", StandardCharsets.UTF_8.name()));

            
            Config mappingsConfig = getConfigs().getConfig(config, "mappings", ConfigFactory.empty());
            for (Map.Entry<String, Object> entry : new Configs().getEntrySet(mappingsConfig)) {
                mappings.put(entry.getKey(), entry.getValue().toString());
            }
            validateArguments();
        }

        @Override
        protected boolean doProcess(Record inputRecord) {
            Record outputRecord = inputRecord.copy();
            AbstractParser.removeAttachments(outputRecord);
            Schema schema = (Schema) inputRecord.getFirstValue("valueSchema");
            org.apache.avro.Schema avroSchema = (org.apache.avro.Schema) inputRecord.getFirstValue(schemaField);
            if (schema == null) {
                //inputRecord.replaceValues("valueSchema", AVRO_CONVERTER.toConnectSchema(avroSchema));
            }
            Object value = inputRecord.getFirstValue(Fields.ATTACHMENT_BODY);
            switch (converterType.toLowerCase()) {
                case "string":
                    outputRecord.replaceValues(valueField, ((String) value).getBytes(characterSet));
                    break;
                case "json":
                    outputRecord.replaceValues(valueField, JSON_CONVERTER.toConnectData(topicField, ((String) value.toString()).getBytes(characterSet)).value());
                    break;
                case "avro":
                default:
                    outputRecord.replaceValues(valueField, AVRO_CONVERTER.toConnectData(avroSchema, value).value());
            }
            // pass record to next command in chain:
            return super.doProcess(outputRecord);
        }

    }

}