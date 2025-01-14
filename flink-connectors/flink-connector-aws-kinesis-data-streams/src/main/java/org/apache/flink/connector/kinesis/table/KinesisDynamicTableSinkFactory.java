/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.kinesis.table;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.base.table.AsyncDynamicTableSinkFactory;
import org.apache.flink.connector.kinesis.sink.KinesisDataStreamsSinkElementConverter;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.SerializationFormatFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;

import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.apache.flink.connector.kinesis.table.KinesisConnectorOptions.AWS_REGION;
import static org.apache.flink.connector.kinesis.table.KinesisConnectorOptions.SINK_FAIL_ON_ERROR;
import static org.apache.flink.connector.kinesis.table.KinesisConnectorOptions.SINK_PARTITIONER;
import static org.apache.flink.connector.kinesis.table.KinesisConnectorOptions.SINK_PARTITIONER_FIELD_DELIMITER;
import static org.apache.flink.connector.kinesis.table.KinesisConnectorOptions.STREAM;
import static org.apache.flink.connector.kinesis.table.KinesisDataStreamsConnectorOptionsUtils.KINESIS_CLIENT_PROPERTIES_KEY;
import static org.apache.flink.table.factories.FactoryUtil.FORMAT;

/** Factory for creating {@link KinesisDynamicSink}. */
@Internal
public class KinesisDynamicTableSinkFactory extends AsyncDynamicTableSinkFactory {
    public static final String IDENTIFIER = "kinesis";

    @Override
    public DynamicTableSink createDynamicTableSink(Context context) {

        FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
        ReadableConfig tableOptions = helper.getOptions();
        ResolvedCatalogTable catalogTable = context.getCatalogTable();
        DataType physicalDataType = catalogTable.getResolvedSchema().toPhysicalRowDataType();

        // initialize the table format early in order to register its consumedOptionKeys
        // in the TableFactoryHelper, as those are needed for correct option validation
        EncodingFormat<SerializationSchema<RowData>> encodingFormat =
                helper.discoverEncodingFormat(SerializationFormatFactory.class, FORMAT);

        KinesisDataStreamsConnectorOptionsUtils optionsUtils =
                new KinesisDataStreamsConnectorOptionsUtils(
                        catalogTable.getOptions(),
                        tableOptions,
                        (RowType) physicalDataType.getLogicalType(),
                        catalogTable.getPartitionKeys(),
                        context.getClassLoader());
        // validate the data types of the table options
        helper.validateExcept(optionsUtils.getNonValidatedPrefixes().toArray(new String[0]));

        // Validate option values
        validateKinesisPartitioner(tableOptions, catalogTable);
        Properties properties = optionsUtils.getValidatedSinkConfigurations();

        KinesisDynamicSink.KinesisDynamicTableSinkBuilder builder =
                new KinesisDynamicSink.KinesisDynamicTableSinkBuilder();

        builder.setStream((String) properties.get(STREAM.key()))
                .setKinesisClientProperties(
                        (Properties) properties.get(KINESIS_CLIENT_PROPERTIES_KEY))
                .setEncodingFormat(encodingFormat)
                .setConsumedDataType(physicalDataType)
                .setPartitioner(
                        (KinesisDataStreamsSinkElementConverter.PartitionKeyGenerator<RowData>)
                                properties.get(SINK_PARTITIONER.key()));
        addAsyncOptionsToBuilder(properties, builder);
        Optional.ofNullable((Boolean) properties.get(SINK_FAIL_ON_ERROR.key()))
                .ifPresent(builder::setFailOnError);
        return builder.build();
    }

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        final Set<ConfigOption<?>> options = new HashSet<>();
        options.add(STREAM);
        options.add(FORMAT);
        options.add(AWS_REGION);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        final Set<ConfigOption<?>> options = super.optionalOptions();
        options.add(SINK_PARTITIONER);
        options.add(SINK_PARTITIONER_FIELD_DELIMITER);
        options.add(SINK_FAIL_ON_ERROR);
        return KinesisDataStreamsConnectorOptionsUtils.KinesisProducerOptionsMapper
                .addDeprecatedKeys(options);
    }

    private static void validateKinesisPartitioner(
            ReadableConfig tableOptions, CatalogTable targetTable) {
        tableOptions
                .getOptional(SINK_PARTITIONER)
                .ifPresent(
                        partitioner -> {
                            if (targetTable.isPartitioned()) {
                                throw new ValidationException(
                                        String.format(
                                                "Cannot set %s option for a table defined with a PARTITIONED BY clause",
                                                SINK_PARTITIONER.key()));
                            }
                        });
    }
}
