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
import org.apache.flink.connector.kinesis.sink.KinesisDataStreamsSinkElementConverter.PartitionKeyGenerator;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.RowType.RowField;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link PartitionKeyGenerator} of {@link RowData} elements that constructs the partition key
 * from a list of field names.
 *
 * <p>The key is constructed by concatenating the string representations of a list of fields
 * projected from an input element. A fixed prefix can be optionally configured in order to speed up
 * the key construction process.
 *
 * <p>Resulting partition key values are trimmed to the maximum length allowed by Kinesis.
 */
@Internal
public final class RowDataFieldsKinesisPartitionKeyGenerator
        implements PartitionKeyGenerator<RowData> {

    private static final long serialVersionUID = 1L;

    /**
     * Allowed maximum length limit of a partition key.
     *
     * @link
     *     https://docs.aws.amazon.com/kinesis/latest/APIReference/API_PutRecord.html#API_PutRecord_RequestSyntax
     */
    public static final int MAX_PARTITION_KEY_LENGTH = 256;

    /** Default delimiter for {@link RowDataFieldsKinesisPartitionKeyGenerator#delimiter}. */
    public static final String DEFAULT_DELIMITER = String.valueOf('|');

    /** The character used to delimit field values in the concatenated partition key string. */
    private final String delimiter;

    /**
     * A list of field names used to extract the partition key for a record that will be written to
     * a Kinesis stream.
     */
    private final List<String> fieldNames;

    /**
     * A map of getter functions to dynamically extract the field values for all {@link
     * RowDataFieldsKinesisPartitionKeyGenerator#fieldNames} from an input record.
     */
    private final Map<String, RowData.FieldGetter> dynamicFieldGetters;

    /**
     * A buffer used to accumulate the concatenation of all field values that form the partition
     * key.
     */
    private final StringBuilder keyBuffer = new StringBuilder();

    /**
     * A prefix of static field values to be used instead of the corresponding {@link
     * RowDataFieldsKinesisPartitionKeyGenerator#dynamicFieldGetters} entries.
     */
    private Map<String, String> staticFields = Collections.emptyMap();

    /**
     * The length of the static prefix of the {@link
     * RowDataFieldsKinesisPartitionKeyGenerator#keyBuffer} (derived from the values in {@link
     * RowDataFieldsKinesisPartitionKeyGenerator#staticFields}).
     */
    private int keyBufferStaticPrefixLength = 0;

    /**
     * The length of the prefix in {@link RowDataFieldsKinesisPartitionKeyGenerator#fieldNames} for
     * which static field values are present in {@link
     * RowDataFieldsKinesisPartitionKeyGenerator#staticFields}.
     */
    private int fieldNamesStaticPrefixLength = 0;

    public RowDataFieldsKinesisPartitionKeyGenerator(
            RowType physicalType, List<String> partitionKeys) {
        this(physicalType, partitionKeys, DEFAULT_DELIMITER);
    }

    public RowDataFieldsKinesisPartitionKeyGenerator(
            RowType physicalType, List<String> partitionKeys, String delimiter) {
        Preconditions.checkNotNull(physicalType, "physicalType");
        Preconditions.checkNotNull(partitionKeys, "partitionKeys");
        Preconditions.checkNotNull(delimiter, "delimiter");
        Preconditions.checkArgument(
                !partitionKeys.isEmpty(),
                "Cannot create a RowDataFieldsKinesisPartitioner for a non-partitioned table");
        Preconditions.checkArgument(
                partitionKeys.size() == new HashSet<>(partitionKeys).size(),
                "The sequence of partition keys cannot contain duplicates");

        List<String> fieldsList = physicalType.getFieldNames();

        List<String> badKeyNames = new ArrayList<>();
        List<String> badKeyTypes = new ArrayList<>();

        for (String fieldName : partitionKeys) {
            int index = fieldsList.indexOf(fieldName);
            if (index < 0) {
                badKeyNames.add(fieldName);
            } else if (!LogicalTypeChecks.hasWellDefinedString(physicalType.getTypeAt(index))) {
                badKeyTypes.add(fieldName);
            }
        }

        Preconditions.checkArgument(
                badKeyNames.size() == 0,
                "The following partition keys are not present in the table: %s",
                String.join(", ", badKeyNames));
        Preconditions.checkArgument(
                badKeyTypes.size() == 0,
                "The following partition keys have types that are not supported by Kinesis: %s",
                String.join(", ", badKeyTypes));

        this.delimiter = delimiter;
        this.fieldNames = partitionKeys;
        this.dynamicFieldGetters = new HashMap<>();
        for (String fieldName : partitionKeys) {
            RowField field = physicalType.getFields().get(fieldsList.indexOf(fieldName));

            RowData.FieldGetter fieldGetter =
                    RowData.createFieldGetter(field.getType(), fieldsList.indexOf(field.getName()));

            this.dynamicFieldGetters.put(fieldName, fieldGetter);
        }
    }

    @Override
    public String apply(RowData element) {
        // reset the buffer to the end of the static prefix size
        keyBuffer.setLength(keyBufferStaticPrefixLength);

        // fill in the dynamic part of the buffer
        for (int i = fieldNamesStaticPrefixLength; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            if (!staticFields.containsKey(fieldName)) {
                keyBuffer.append(dynamicFieldGetters.get(fieldName).getFieldOrNull(element));
            } else {
                keyBuffer.append(staticFields.get(fieldName));
            }
            keyBuffer.append(delimiter);

            if (keyBuffer.length() >= MAX_PARTITION_KEY_LENGTH) {
                break; // stop when the buffer length exceeds the allowed partition key size
            }
        }

        // return the accumulated concatenated string trimmed to the max allowed partition key size
        int length = Math.min(keyBuffer.length() - delimiter.length(), MAX_PARTITION_KEY_LENGTH);
        return keyBuffer.substring(0, length);
    }

    /**
     * Update the fixed partition key prefix.
     *
     * @param staticFields An association of (field name, field value) pairs to be used as static
     *     partition key prefix.
     */
    public void setStaticFields(Map<String, String> staticFields) {
        Preconditions.checkArgument(
                isPartitionKeySubset(staticFields.keySet()),
                String.format(
                        "Not all static field names (%s) are part of the partition key (%s).",
                        String.join(", ", staticFields.keySet()), String.join(", ", fieldNames)));
        this.staticFields = new HashMap<>(staticFields);
        updateKeyBufferStaticPrefix();
    }

    /**
     * Check whether the set of field names in {@code candidatePrefix} forms a valid subset of the
     * set of field names defined in {@link RowDataFieldsKinesisPartitionKeyGenerator#fieldNames}.
     *
     * @param candidateSubset A set of field names forming a candidate subset of {@link
     *     RowDataFieldsKinesisPartitionKeyGenerator#fieldNames}.
     * @return true if and only if the {@code candidatePrefix} is a proper subset of {@link
     *     RowDataFieldsKinesisPartitionKeyGenerator#fieldNames}.
     */
    private boolean isPartitionKeySubset(Set<String> candidateSubset) {
        return new HashSet<>(fieldNames).containsAll(candidateSubset);
    }

    /**
     * Pre-fills a prefix with static partition key values in the {@link
     * RowDataFieldsKinesisPartitionKeyGenerator#keyBufferStaticPrefixLength} buffer based on the
     * currently set {@link RowDataFieldsKinesisPartitionKeyGenerator#staticFields}.
     */
    private void updateKeyBufferStaticPrefix() {
        // update the fixed prefix and its cumulative length
        keyBuffer.setLength(0);
        fieldNamesStaticPrefixLength = 0;
        for (String fieldName : fieldNames) {
            if (staticFields.containsKey(fieldName)) {
                keyBuffer.append(staticFields.get(fieldName));
                keyBuffer.append(delimiter);
                fieldNamesStaticPrefixLength++;
            } else {
                break; // stop on first static field
            }
        }
        keyBufferStaticPrefixLength = keyBuffer.length();
    }

    // --------------------------------------------------------------------------------------------
    // Value semantics for equals and hashCode
    // --------------------------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RowDataFieldsKinesisPartitionKeyGenerator that =
                (RowDataFieldsKinesisPartitionKeyGenerator) o;
        return Objects.equals(this.delimiter, that.delimiter)
                && Objects.equals(this.fieldNames, that.fieldNames)
                && Objects.equals(this.staticFields, that.staticFields)
                && Objects.equals(
                        this.keyBufferStaticPrefixLength, that.keyBufferStaticPrefixLength)
                && Objects.equals(
                        this.fieldNamesStaticPrefixLength, that.fieldNamesStaticPrefixLength);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                delimiter,
                fieldNames,
                staticFields,
                keyBufferStaticPrefixLength,
                fieldNamesStaticPrefixLength);
    }
}
