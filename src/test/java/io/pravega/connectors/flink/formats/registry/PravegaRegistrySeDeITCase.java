/**
 * Copyright Pravega Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pravega.connectors.flink.formats.registry;

import io.pravega.client.stream.Serializer;
import io.pravega.connectors.flink.formats.registry.testProto.testMessage;
import io.pravega.connectors.flink.table.catalog.pravega.PravegaCatalog;
import io.pravega.connectors.flink.table.catalog.pravega.util.PravegaSchemaUtils;
import io.pravega.connectors.flink.util.FlinkPravegaUtils;
import io.pravega.connectors.flink.utils.SchemaRegistryTestEnvironment;
import io.pravega.connectors.flink.utils.runtime.PravegaRuntime;
import io.pravega.connectors.flink.utils.runtime.SchemaRegistryRuntime;
import io.pravega.schemaregistry.client.SchemaRegistryClientConfig;
import io.pravega.schemaregistry.client.SchemaRegistryClientFactory;
import io.pravega.schemaregistry.contract.data.SerializationFormat;
import io.pravega.schemaregistry.serializer.avro.schemas.AvroSchema;
import io.pravega.schemaregistry.serializer.json.schemas.JSONSchema;
import io.pravega.schemaregistry.serializer.protobuf.schemas.ProtobufSchema;
import io.pravega.schemaregistry.serializer.shared.impl.SerializerConfig;
import io.pravega.schemaregistry.serializers.SerializerFactory;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.formats.avro.typeutils.AvroSchemaConverter;
import org.apache.flink.formats.common.TimestampFormat;
import org.apache.flink.formats.json.JsonFormatOptions;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.util.DataFormatConverters;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.flink.table.api.DataTypes.ARRAY;
import static org.apache.flink.table.api.DataTypes.BIGINT;
import static org.apache.flink.table.api.DataTypes.BOOLEAN;
import static org.apache.flink.table.api.DataTypes.BYTES;
import static org.apache.flink.table.api.DataTypes.DATE;
import static org.apache.flink.table.api.DataTypes.DECIMAL;
import static org.apache.flink.table.api.DataTypes.DOUBLE;
import static org.apache.flink.table.api.DataTypes.FIELD;
import static org.apache.flink.table.api.DataTypes.FLOAT;
import static org.apache.flink.table.api.DataTypes.INT;
import static org.apache.flink.table.api.DataTypes.MAP;
import static org.apache.flink.table.api.DataTypes.MULTISET;
import static org.apache.flink.table.api.DataTypes.ROW;
import static org.apache.flink.table.api.DataTypes.SMALLINT;
import static org.apache.flink.table.api.DataTypes.STRING;
import static org.apache.flink.table.api.DataTypes.TIME;
import static org.apache.flink.table.api.DataTypes.TIMESTAMP;
import static org.apache.flink.table.api.DataTypes.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
import static org.apache.flink.table.api.DataTypes.TINYINT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Intergration Test for Pravega Registry serialization and deserialization
 * schema.
 */
@SuppressWarnings("checkstyle:StaticVariableName")
public class PravegaRegistrySeDeITCase {
        private static final String TEST_AVRO_CATALOG_NAME = "mycatalog1";
        private static final String TEST_JSON_CATALOG_NAME = "mycatalog2";
        private static final String TEST_PROTOBUF_CATALOG_NAME = "mycatalog3";

        /** Avro fields */
        private static final String AVRO_TEST_STREAM = "stream1";
        private static Schema avroSchema = null;
        private static RowType avroRowType = null;
        private static TypeInformation<RowData> avroTypeInfo = null;

        /** Json fields */
        private static final String JSON_TEST_STREAM = "stream2";
        private static JSONSchema<JsonNode> jsonSchema = null;
        private static RowType jsonRowType = null;
        private static TypeInformation<RowData> jsonTypeInfo = null;
        private static DataType jsonDataType = null;

        private static final boolean FAIL_ON_MISSING_FIELD = false;
        private static final boolean IGNORE_PARSE_ERRORS = false;
        private static final TimestampFormat TIMESTAMP_FORMAT = TimestampFormat.ISO_8601;
        private static final JsonFormatOptions.MapNullKeyMode MAP_NULL_KEY_MODE = JsonFormatOptions.MapNullKeyMode.FAIL;
        private static final String MAP_NULL_KEY_LITERAL = "null";
        private static final boolean ENCODE_DECIMAL_AS_PLAIN_NUMBER = false;

        /** Protobuf fields */
        private static final String PROTOBUF_TEST_STREAM = "stream3";
        private static ProtobufSchema<?> protobufSchema = null;
        private static RowType protobufRowType = null;
        private static TypeInformation<RowData> protobufTypeInfo = null;
        private static DataType protobufDataType = null;

        private static final boolean PB_IGNORE_PARSE_ERRORS = false;
        private static final String PB_MESSAGE_CLASS_NAME = "io.pravega.connectors.flink.formats.registry.testProto.testMessage";
        private static final boolean PB_READ_DEFAULT_VALUES = false;
        private static final String PB_WRITE_NULL_STRING_LITERAL = "null";

        /** Setup utility */
        private static final SchemaRegistryTestEnvironment SCHEMA_REGISTRY = new SchemaRegistryTestEnvironment(
                        PravegaRuntime.container(), SchemaRegistryRuntime.container());

        @BeforeAll
        public static void setupPravega() throws Exception {
                SCHEMA_REGISTRY.startUp();
        }

        @AfterAll
        public static void tearDownPravega() throws Exception {
                SCHEMA_REGISTRY.tearDown();
        }

        @Test
        public void testAvroSerializeDeserialize() throws Exception {
                Map<String, String> properties = new HashMap<>();
                properties.put("connector", "pravega");
                properties.put("controller-uri", SCHEMA_REGISTRY.operator().getControllerUri().toString());
                properties.put("format", "pravega-registry");
                properties.put("pravega-registry.uri",
                                SCHEMA_REGISTRY.schemaRegistryOperator().getSchemaRegistryUri().toString());
                properties.put("pravega-registry.format", "Avro");
                final PravegaCatalog avroCatalog = new PravegaCatalog(TEST_AVRO_CATALOG_NAME,
                                SCHEMA_REGISTRY.operator().getScope(), properties,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                "Avro");
                initAvro();
                avroCatalog.open();

                final GenericRecord record = new GenericData.Record(avroSchema);
                record.put(0, true);
                record.put(1, (int) Byte.MAX_VALUE);
                record.put(2, (int) Short.MAX_VALUE);
                record.put(3, 33);
                record.put(4, 44L);
                record.put(5, 12.34F);
                record.put(6, 23.45);
                record.put(7, "hello avro");
                record.put(8, ByteBuffer.wrap(new byte[] { 1, 2, 4, 5, 6, 7, 8, 12 }));

                record.put(
                                9, ByteBuffer.wrap(BigDecimal.valueOf(123456789, 6).unscaledValue().toByteArray()));

                List<Double> doubles = new ArrayList<>();
                doubles.add(1.2);
                doubles.add(3.4);
                doubles.add(567.8901);
                record.put(10, doubles);

                record.put(11, 18397);
                record.put(12, 10087);
                record.put(13, 1589530213123L);
                record.put(14, 1589530213122L);

                Map<String, Long> map = new HashMap<>();
                map.put("flink", 12L);
                map.put("avro", 23L);
                record.put(15, map);

                Map<String, Map<String, Integer>> map2map = new HashMap<>();
                Map<String, Integer> innerMap = new HashMap<>();
                innerMap.put("inner_key1", 123);
                innerMap.put("inner_key2", 234);
                map2map.put("outer_key", innerMap);
                record.put(16, map2map);

                List<Integer> list1 = Arrays.asList(1, 2, 3, 4, 5, 6);
                List<Integer> list2 = Arrays.asList(11, 22, 33, 44, 55);
                Map<String, List<Integer>> map2list = new HashMap<>();
                map2list.put("list1", list1);
                map2list.put("list2", list2);
                record.put(17, map2list);

                Map<String, String> map2 = new HashMap<>();
                map2.put("key1", null);
                record.put(18, map2);

                PravegaRegistryRowDataSerializationSchema serializationSchema = new PravegaRegistryRowDataSerializationSchema(
                                avroRowType,
                                AVRO_TEST_STREAM, SerializationFormat.Avro,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                TIMESTAMP_FORMAT, MAP_NULL_KEY_MODE, MAP_NULL_KEY_LITERAL,
                                ENCODE_DECIMAL_AS_PLAIN_NUMBER, PB_MESSAGE_CLASS_NAME, PB_IGNORE_PARSE_ERRORS,
                                PB_READ_DEFAULT_VALUES, PB_WRITE_NULL_STRING_LITERAL);
                serializationSchema.open(null);
                PravegaRegistryRowDataDeserializationSchema deserializationSchema = new PravegaRegistryRowDataDeserializationSchema(
                                avroRowType, avroTypeInfo,
                                AVRO_TEST_STREAM,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                FAIL_ON_MISSING_FIELD, IGNORE_PARSE_ERRORS, TIMESTAMP_FORMAT, PB_MESSAGE_CLASS_NAME,
                                PB_IGNORE_PARSE_ERRORS,
                                PB_READ_DEFAULT_VALUES, PB_WRITE_NULL_STRING_LITERAL);
                deserializationSchema.open(null);

                SchemaRegistryClientConfig schemaRegistryClientConfig = SchemaRegistryClientConfig.builder()
                                .schemaRegistryUri(SCHEMA_REGISTRY.schemaRegistryOperator().getSchemaRegistryUri())
                                .build();
                SerializerConfig config = SerializerConfig.builder()
                                .registryConfig(schemaRegistryClientConfig)
                                .namespace(SCHEMA_REGISTRY.operator().getScope())
                                .groupId(AVRO_TEST_STREAM)
                                .build();
                Serializer<GenericRecord> serializer = SerializerFactory.avroSerializer(config,
                                AvroSchema.ofRecord(avroSchema));

                byte[] input = FlinkPravegaUtils
                                .byteBufferToArray(serializer.serialize(record));
                RowData rowData = deserializationSchema.deserialize(input);
                byte[] output = serializationSchema.serialize(rowData);

                assertThat(output).isEqualTo(input);

                avroCatalog.close();
        }

        @Test
        public void testJsonDeserialize() throws Exception {
                Map<String, String> properties = new HashMap<>();
                properties.put("connector", "pravega");
                properties.put("controller-uri", SCHEMA_REGISTRY.operator().getControllerUri().toString());
                properties.put("format", "pravega-registry");
                properties.put("pravega-registry.uri",
                                SCHEMA_REGISTRY.schemaRegistryOperator().getSchemaRegistryUri().toString());
                properties.put("pravega-registry.format", "Json");
                final PravegaCatalog jsonCatalog = new PravegaCatalog(TEST_JSON_CATALOG_NAME,
                                SCHEMA_REGISTRY.operator().getScope(), properties,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                "Json");
                initJson();
                jsonCatalog.open();

                byte tinyint = 'c';
                short smallint = 128;
                int intValue = 45536;
                float floatValue = 33.333F;
                long bigint = 1238123899121L;
                String name = "asdlkjasjkdla998y1122";
                byte[] bytes = new byte[1024];
                ThreadLocalRandom.current().nextBytes(bytes);
                BigDecimal decimal = new BigDecimal("123.456789");
                Double[] doubles = new Double[] { 1.1, 2.2, 3.3 };
                LocalDate date = LocalDate.parse("1990-10-14");
                LocalTime time = LocalTime.parse("12:12:43");
                Timestamp timestamp3 = Timestamp.valueOf("1990-10-14 12:12:43.123");
                Timestamp timestamp9 = Timestamp.valueOf("1990-10-14 12:12:43.123456789");
                Instant timestampWithLocalZone = LocalDateTime.of(1990, 10, 14, 12, 12, 43, 123456789)
                                .atOffset(ZoneOffset.of("Z"))
                                .toInstant();

                Map<String, Long> map = new HashMap<>();
                map.put("flink", 123L);

                Map<String, Integer> multiSet = new HashMap<>();
                multiSet.put("blink", 2);

                Map<String, Map<String, Integer>> nestedMap = new HashMap<>();
                Map<String, Integer> innerMap = new HashMap<>();
                innerMap.put("key", 234);
                nestedMap.put("inner_map", innerMap);

                ObjectMapper objectMapper = new ObjectMapper();
                ArrayNode doubleNode = objectMapper.createArrayNode().add(1.1D).add(2.2D).add(3.3D);

                // Root
                ObjectNode root = objectMapper.createObjectNode();
                root.put("bool", true);
                root.put("tinyint", tinyint);
                root.put("smallint", smallint);
                root.put("int", intValue);
                root.put("bigint", bigint);
                root.put("float", floatValue);
                root.put("name", name);
                root.put("bytes", bytes);
                root.put("decimal", decimal);
                root.set("doubles", doubleNode);
                root.put("date", "1990-10-14");
                root.put("time", "12:12:43");
                root.put("timestamp3", "1990-10-14T12:12:43.123");
                root.put("timestamp9", "1990-10-14T12:12:43.123456789");
                root.put("timestampWithLocalZone", "1990-10-14T12:12:43.123456789Z");
                root.putObject("map").put("flink", 123);
                root.putObject("multiSet").put("blink", 2);
                root.putObject("map2map").putObject("inner_map").put("key", 234);

                SchemaRegistryClientConfig schemaRegistryClientConfig = SchemaRegistryClientConfig.builder()
                                .schemaRegistryUri(SCHEMA_REGISTRY.schemaRegistryOperator().getSchemaRegistryUri())
                                .build();
                SerializerConfig serializerConfig = SerializerConfig.builder()
                                .registryConfig(schemaRegistryClientConfig)
                                .namespace(SCHEMA_REGISTRY.operator().getScope())
                                .groupId(JSON_TEST_STREAM)
                                .build();
                Serializer<JsonNode> serializer = new PravegaRegistryRowDataSerializationSchema.FlinkJsonSerializer(
                                JSON_TEST_STREAM,
                                SchemaRegistryClientFactory.withNamespace(SCHEMA_REGISTRY.operator().getScope(),
                                                schemaRegistryClientConfig),
                                jsonSchema,
                                serializerConfig.getEncoder(),
                                serializerConfig.isRegisterSchema(),
                                serializerConfig.isWriteEncodingHeader());

                byte[] serializedJson = serializer.serialize(root).array();

                // test deserialization
                PravegaRegistryRowDataDeserializationSchema deserializationSchema = new PravegaRegistryRowDataDeserializationSchema(
                                jsonRowType, jsonTypeInfo, JSON_TEST_STREAM,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                FAIL_ON_MISSING_FIELD, IGNORE_PARSE_ERRORS, TIMESTAMP_FORMAT, PB_MESSAGE_CLASS_NAME,
                                PB_IGNORE_PARSE_ERRORS, PB_READ_DEFAULT_VALUES, PB_WRITE_NULL_STRING_LITERAL);
                deserializationSchema.open(null);

                Row expected = new Row(18);
                expected.setField(0, true);
                expected.setField(1, tinyint);
                expected.setField(2, smallint);
                expected.setField(3, intValue);
                expected.setField(4, bigint);
                expected.setField(5, floatValue);
                expected.setField(6, name);
                expected.setField(7, bytes);
                expected.setField(8, decimal);
                expected.setField(9, doubles);
                expected.setField(10, date);
                expected.setField(11, time);
                expected.setField(12, timestamp3.toLocalDateTime());
                expected.setField(13, timestamp9.toLocalDateTime());
                expected.setField(14, timestampWithLocalZone);
                expected.setField(15, map);
                expected.setField(16, multiSet);
                expected.setField(17, nestedMap);

                RowData rowData = deserializationSchema.deserialize(serializedJson);
                Row actual = convertToExternal(rowData, jsonDataType);
                assertThat(actual).isEqualTo(expected);

                // test serialization
                PravegaRegistryRowDataSerializationSchema serializationSchema = new PravegaRegistryRowDataSerializationSchema(
                                jsonRowType, JSON_TEST_STREAM, SerializationFormat.Json,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                TIMESTAMP_FORMAT, MAP_NULL_KEY_MODE, MAP_NULL_KEY_LITERAL,
                                ENCODE_DECIMAL_AS_PLAIN_NUMBER, PB_MESSAGE_CLASS_NAME, PB_IGNORE_PARSE_ERRORS,
                                PB_READ_DEFAULT_VALUES, PB_WRITE_NULL_STRING_LITERAL);
                serializationSchema.open(null);

                byte[] actualBytes = serializationSchema.serialize(rowData);
                assertThat(new String(actualBytes)).isEqualTo(new String(serializedJson));

                jsonCatalog.close();
        }

        @Test
        public void testProtobufDeserialize() throws Exception {

                Map<String, String> properties = new HashMap<>();
                properties.put("connector", "pravega");
                properties.put("controller-uri", SCHEMA_REGISTRY.operator().getControllerUri().toString());
                properties.put("format", "pravega-registry");
                properties.put("pravega-registry.uri",
                                SCHEMA_REGISTRY.schemaRegistryOperator().getSchemaRegistryUri().toString());
                properties.put("pravega-registry.format", "Protobuf");
                final PravegaCatalog protobufCatalog = new PravegaCatalog(TEST_PROTOBUF_CATALOG_NAME,
                                SCHEMA_REGISTRY.operator().getScope(), properties,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                "Protobuf");
                initProtobuf();
                protobufCatalog.open();

                SchemaRegistryClientConfig schemaRegistryClientConfig = SchemaRegistryClientConfig.builder()
                                .schemaRegistryUri(SCHEMA_REGISTRY.schemaRegistryOperator().getSchemaRegistryUri())
                                .build();

                PravegaRegistryRowDataSerializationSchema serializationSchema = new PravegaRegistryRowDataSerializationSchema(
                                protobufRowType,
                                PROTOBUF_TEST_STREAM, SerializationFormat.Protobuf,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                TIMESTAMP_FORMAT, MAP_NULL_KEY_MODE, MAP_NULL_KEY_LITERAL,
                                ENCODE_DECIMAL_AS_PLAIN_NUMBER, PB_MESSAGE_CLASS_NAME, PB_IGNORE_PARSE_ERRORS,
                                PB_READ_DEFAULT_VALUES, PB_WRITE_NULL_STRING_LITERAL);
                serializationSchema.open(null);
                PravegaRegistryRowDataDeserializationSchema deserializationSchema = new PravegaRegistryRowDataDeserializationSchema(
                                protobufRowType, protobufTypeInfo,
                                PROTOBUF_TEST_STREAM,
                                SCHEMA_REGISTRY.operator().getPravegaConfig()
                                                .withDefaultScope(SCHEMA_REGISTRY.operator().getScope())
                                                .withSchemaRegistryURI(SCHEMA_REGISTRY.schemaRegistryOperator()
                                                                .getSchemaRegistryUri()),
                                FAIL_ON_MISSING_FIELD, IGNORE_PARSE_ERRORS, TIMESTAMP_FORMAT, PB_MESSAGE_CLASS_NAME,
                                PB_IGNORE_PARSE_ERRORS, PB_READ_DEFAULT_VALUES, PB_WRITE_NULL_STRING_LITERAL);
                deserializationSchema.open(null);

                SerializerConfig config = SerializerConfig.builder()
                                .registryConfig(schemaRegistryClientConfig)
                                .namespace(SCHEMA_REGISTRY.operator().getScope())
                                .groupId(PROTOBUF_TEST_STREAM)
                                .build();

                Serializer<testMessage> serializer = SerializerFactory.protobufSerializer(config,
                                ProtobufSchema.of(testMessage.class));
                byte[] input = FlinkPravegaUtils
                                .byteBufferToArray(serializer.serialize(testMessage.newBuilder()
                                                .setName("name")
                                                .setField1(1)
                                                .build()));
                RowData rowData = deserializationSchema.deserialize(input);
                byte[] output = serializationSchema.serialize(rowData);

                assertThat(output).isEqualTo(input);

                protobufCatalog.close();

        }

        private static void initAvro() throws Exception {
                final DataType dataType = ROW(
                                FIELD("bool", BOOLEAN()),
                                FIELD("tinyint", TINYINT()),
                                FIELD("smallint", SMALLINT()),
                                FIELD("int", INT()),
                                FIELD("bigint", BIGINT()),
                                FIELD("float", FLOAT()),
                                FIELD("double", DOUBLE()),
                                FIELD("name", STRING()),
                                FIELD("bytes", BYTES()),
                                FIELD("decimal", DECIMAL(19, 6)),
                                FIELD("doubles", ARRAY(DOUBLE())),
                                FIELD("time", TIME(0)),
                                FIELD("date", DATE()),
                                FIELD("timestamp3", TIMESTAMP(3)),
                                FIELD("timestamp3_2", TIMESTAMP(3)),
                                FIELD("map", MAP(STRING(), BIGINT())),
                                FIELD("map2map", MAP(STRING(), MAP(STRING(), INT()))),
                                FIELD("map2array", MAP(STRING(), ARRAY(INT()))),
                                FIELD("nullEntryMap", MAP(STRING(), STRING()))).notNull();
                avroRowType = (RowType) dataType.getLogicalType();
                avroTypeInfo = InternalTypeInfo.of(avroRowType);
                avroSchema = AvroSchemaConverter.convertToSchema(avroRowType);
                SCHEMA_REGISTRY.schemaRegistryOperator().registerSchema(AVRO_TEST_STREAM, AvroSchema.of(avroSchema),
                                SerializationFormat.Avro);
                SCHEMA_REGISTRY.operator().createTestStream(AVRO_TEST_STREAM, 3);
        }

        private static void initJson() throws Exception {
                jsonDataType = ROW(
                                FIELD("bool", BOOLEAN()),
                                FIELD("tinyint", TINYINT()),
                                FIELD("smallint", SMALLINT()),
                                FIELD("int", INT()),
                                FIELD("bigint", BIGINT()),
                                FIELD("float", FLOAT()),
                                FIELD("name", STRING()),
                                FIELD("bytes", BYTES()),
                                FIELD("decimal", DECIMAL(9, 6)),
                                FIELD("doubles", ARRAY(DOUBLE())),
                                FIELD("date", DATE()),
                                FIELD("time", TIME(0)),
                                FIELD("timestamp3", TIMESTAMP(3)),
                                FIELD("timestamp9", TIMESTAMP(9)),
                                FIELD("timestampWithLocalZone", TIMESTAMP_WITH_LOCAL_TIME_ZONE(9)),
                                FIELD("map", MAP(STRING(), BIGINT())),
                                FIELD("multiSet", MULTISET(STRING())),
                                FIELD("map2map", MAP(STRING(), MAP(STRING(), INT()))));
                jsonRowType = (RowType) jsonDataType.getLogicalType();
                jsonTypeInfo = InternalTypeInfo.of(jsonRowType);

                String schemaString = PravegaSchemaUtils.convertToJsonSchemaString(jsonRowType);
                jsonSchema = JSONSchema.of("", schemaString, JsonNode.class);
                SCHEMA_REGISTRY.schemaRegistryOperator().registerSchema(JSON_TEST_STREAM, jsonSchema,
                                SerializationFormat.Json);
                SCHEMA_REGISTRY.operator().createTestStream(JSON_TEST_STREAM, 3);
        }

        private static void initProtobuf() throws Exception {
                protobufDataType = ROW(
                                FIELD("name", STRING()),
                                FIELD("field1", INT())).notNull();
                protobufRowType = (RowType) protobufDataType.getLogicalType();
                protobufTypeInfo = InternalTypeInfo.of(protobufRowType);

                protobufSchema = ProtobufSchema.of(testMessage.class);

                SCHEMA_REGISTRY.schemaRegistryOperator().registerSchema(PROTOBUF_TEST_STREAM,
                                protobufSchema, SerializationFormat.Protobuf);
                SCHEMA_REGISTRY.operator().createTestStream(PROTOBUF_TEST_STREAM, 3);

        }

        @SuppressWarnings("unchecked")
        private static Row convertToExternal(RowData rowData, DataType dataType) {
                return (Row) DataFormatConverters.getConverterForDataType(dataType).toExternal(rowData);
        }
}
