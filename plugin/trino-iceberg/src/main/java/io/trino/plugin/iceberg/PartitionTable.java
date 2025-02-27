/*
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
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.InMemoryRecordSet;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.RowType;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeUtils;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.StructLikeWrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.iceberg.IcebergUtil.getIdentityPartitions;
import static io.trino.plugin.iceberg.TypeConverter.toTrinoType;
import static io.trino.plugin.iceberg.util.Timestamps.timestampTzFromMicros;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.Decimals.isShortDecimal;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_MICROSECOND;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public class PartitionTable
        implements SystemTable
{
    private final TypeManager typeManager;
    private final Table icebergTable;
    private final Optional<Long> snapshotId;
    private final Map<Integer, Type.PrimitiveType> idToTypeMapping;
    private final List<Types.NestedField> nonPartitionPrimitiveColumns;
    private final List<io.trino.spi.type.Type> partitionColumnTypes;
    private final List<io.trino.spi.type.Type> resultTypes;
    private final List<RowType> columnMetricTypes;
    private final ConnectorTableMetadata connectorTableMetadata;

    public PartitionTable(SchemaTableName tableName, TypeManager typeManager, Table icebergTable, Optional<Long> snapshotId)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.icebergTable = requireNonNull(icebergTable, "icebergTable is null");
        this.snapshotId = requireNonNull(snapshotId, "snapshotId is null");
        this.idToTypeMapping = icebergTable.schema().columns().stream()
                .filter(column -> column.type().isPrimitiveType())
                .collect(Collectors.toMap(Types.NestedField::fieldId, (column) -> column.type().asPrimitiveType()));

        List<Types.NestedField> columns = icebergTable.schema().columns();
        List<PartitionField> partitionFields = icebergTable.spec().fields();

        ImmutableList.Builder<ColumnMetadata> columnMetadataBuilder = ImmutableList.builder();

        List<ColumnMetadata> partitionColumnsMetadata = getPartitionColumnsMetadata(partitionFields, icebergTable.schema());
        this.partitionColumnTypes = partitionColumnsMetadata.stream()
                .map(ColumnMetadata::getType)
                .collect(toImmutableList());
        columnMetadataBuilder.addAll(partitionColumnsMetadata);

        Set<Integer> identityPartitionIds = getIdentityPartitions(icebergTable.spec()).keySet().stream()
                .map(PartitionField::sourceId)
                .collect(toSet());

        this.nonPartitionPrimitiveColumns = columns.stream()
                .filter(column -> !identityPartitionIds.contains(column.fieldId()) && column.type().isPrimitiveType())
                .collect(toImmutableList());

        ImmutableList.of("row_count", "file_count", "total_size")
                .forEach(metric -> columnMetadataBuilder.add(new ColumnMetadata(metric, BIGINT)));

        List<ColumnMetadata> columnMetricsMetadata = getColumnMetadata(nonPartitionPrimitiveColumns);
        columnMetadataBuilder.addAll(columnMetricsMetadata);

        this.columnMetricTypes = columnMetricsMetadata.stream().map(m -> (RowType) m.getType()).collect(toImmutableList());

        ImmutableList<ColumnMetadata> columnMetadata = columnMetadataBuilder.build();
        this.resultTypes = columnMetadata.stream()
                .map(ColumnMetadata::getType)
                .collect(toImmutableList());
        this.connectorTableMetadata = new ConnectorTableMetadata(tableName, columnMetadata);
    }

    @Override
    public Distribution getDistribution()
    {
        return Distribution.SINGLE_COORDINATOR;
    }

    @Override
    public ConnectorTableMetadata getTableMetadata()
    {
        return connectorTableMetadata;
    }

    private List<ColumnMetadata> getPartitionColumnsMetadata(List<PartitionField> fields, Schema schema)
    {
        return fields.stream()
                .map(field -> new ColumnMetadata(
                        field.name(),
                        toTrinoType(field.transform().getResultType(schema.findType(field.sourceId())), typeManager)))
                .collect(toImmutableList());
    }

    private List<ColumnMetadata> getColumnMetadata(List<Types.NestedField> columns)
    {
        return columns.stream().map(column -> new ColumnMetadata(column.name(),
                RowType.from(ImmutableList.of(
                        new RowType.Field(Optional.of("min"), toTrinoType(column.type(), typeManager)),
                        new RowType.Field(Optional.of("max"), toTrinoType(column.type(), typeManager)),
                        new RowType.Field(Optional.of("null_count"), BIGINT)))))
                .collect(toImmutableList());
    }

    @Override
    public RecordCursor cursor(ConnectorTransactionHandle transactionHandle, ConnectorSession session, TupleDomain<Integer> constraint)
    {
        // TODO instead of cursor use pageSource method.
        if (snapshotId.isEmpty()) {
            return new InMemoryRecordSet(resultTypes, ImmutableList.of()).cursor();
        }
        TableScan tableScan = icebergTable.newScan()
                .useSnapshot(snapshotId.get())
                .includeColumnStats();
        return buildRecordCursor(getPartitions(tableScan), icebergTable.spec().fields());
    }

    private Map<StructLikeWrapper, Partition> getPartitions(TableScan tableScan)
    {
        try (CloseableIterable<FileScanTask> fileScanTasks = tableScan.planFiles()) {
            Map<StructLikeWrapper, Partition> partitions = new HashMap<>();

            for (FileScanTask fileScanTask : fileScanTasks) {
                DataFile dataFile = fileScanTask.file();
                Types.StructType structType = fileScanTask.spec().partitionType();
                StructLike partitionStruct = dataFile.partition();
                StructLikeWrapper partitionWrapper = StructLikeWrapper.forType(structType).set(partitionStruct);

                if (!partitions.containsKey(partitionWrapper)) {
                    Partition partition = new Partition(
                            idToTypeMapping,
                            nonPartitionPrimitiveColumns,
                            partitionStruct,
                            dataFile.recordCount(),
                            dataFile.fileSizeInBytes(),
                            toMap(dataFile.lowerBounds()),
                            toMap(dataFile.upperBounds()),
                            dataFile.nullValueCounts(),
                            dataFile.columnSizes());
                    partitions.put(partitionWrapper, partition);
                    continue;
                }

                Partition partition = partitions.get(partitionWrapper);
                partition.incrementFileCount();
                partition.incrementRecordCount(dataFile.recordCount());
                partition.incrementSize(dataFile.fileSizeInBytes());
                partition.updateMin(toMap(dataFile.lowerBounds()), dataFile.nullValueCounts(), dataFile.recordCount());
                partition.updateMax(toMap(dataFile.upperBounds()), dataFile.nullValueCounts(), dataFile.recordCount());
                partition.updateNullCount(dataFile.nullValueCounts());
            }

            return partitions;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RecordCursor buildRecordCursor(Map<StructLikeWrapper, Partition> partitions, List<PartitionField> partitionFields)
    {
        List<Type> partitionTypes = partitionTypes(partitionFields);
        List<? extends Class<?>> partitionColumnClass = partitionTypes.stream()
                .map(type -> type.typeId().javaClass())
                .collect(toImmutableList());
        int columnCounts = partitionColumnTypes.size() + 3 + columnMetricTypes.size();

        ImmutableList.Builder<List<Object>> records = ImmutableList.builder();

        for (Partition partition : partitions.values()) {
            List<Object> row = new ArrayList<>(columnCounts);

            // add data for partition columns
            for (int i = 0; i < partitionColumnTypes.size(); i++) {
                row.add(convert(partition.getValues().get(i, partitionColumnClass.get(i)), partitionTypes.get(i)));
            }

            // add the top level metrics.
            row.add(partition.getRecordCount());
            row.add(partition.getFileCount());
            row.add(partition.getSize());

            // add column level metrics
            for (int i = 0; i < columnMetricTypes.size(); i++) {
                if (!partition.hasValidColumnMetrics()) {
                    row.add(null);
                    continue;
                }
                Integer fieldId = nonPartitionPrimitiveColumns.get(i).fieldId();
                Type.PrimitiveType type = idToTypeMapping.get(fieldId);
                Object min = convert(partition.getMinValues().get(fieldId), type);
                Object max = convert(partition.getMaxValues().get(fieldId), type);
                Long nullCount = partition.getNullCounts().get(fieldId);
                row.add(getColumnMetricBlock(columnMetricTypes.get(i), min, max, nullCount));
            }

            records.add(row);
        }

        return new InMemoryRecordSet(resultTypes, records.build()).cursor();
    }

    private List<Type> partitionTypes(List<PartitionField> partitionFields)
    {
        ImmutableList.Builder<Type> partitionTypeBuilder = ImmutableList.builder();
        for (PartitionField partitionField : partitionFields) {
            Type.PrimitiveType sourceType = idToTypeMapping.get(partitionField.sourceId());
            Type type = partitionField.transform().getResultType(sourceType);
            partitionTypeBuilder.add(type);
        }
        return partitionTypeBuilder.build();
    }

    private static Block getColumnMetricBlock(RowType columnMetricType, Object min, Object max, Long nullCount)
    {
        BlockBuilder rowBlockBuilder = columnMetricType.createBlockBuilder(null, 1);
        BlockBuilder builder = rowBlockBuilder.beginBlockEntry();
        List<RowType.Field> fields = columnMetricType.getFields();
        TypeUtils.writeNativeValue(fields.get(0).getType(), builder, min);
        TypeUtils.writeNativeValue(fields.get(1).getType(), builder, max);
        TypeUtils.writeNativeValue(fields.get(2).getType(), builder, nullCount);

        rowBlockBuilder.closeEntry();
        return columnMetricType.getObject(rowBlockBuilder, 0);
    }

    private Map<Integer, Object> toMap(Map<Integer, ByteBuffer> idToMetricMap)
    {
        return Partition.toMap(idToTypeMapping, idToMetricMap);
    }

    /**
     * Convert value from Iceberg representation to Trino representation.
     */
    public static Object convert(Object value, Type type)
    {
        if (value == null) {
            return null;
        }
        if (type instanceof Types.StringType) {
            // Partition values are passed as String, but min/max values are passed as a CharBuffer
            if (value instanceof CharBuffer) {
                value = new String(((CharBuffer) value).array());
            }
            return utf8Slice(((String) value));
        }
        if (type instanceof Types.BinaryType) {
            // TODO the client sees the bytearray's tostring ouput instead of seeing actual bytes, needs to be fixed.
            return ((ByteBuffer) value).array().clone();
        }
        if (type instanceof Types.TimestampType) {
            long epochMicros = (long) value;
            if (((Types.TimestampType) type).shouldAdjustToUTC()) {
                return timestampTzFromMicros(epochMicros, TimeZoneKey.UTC_KEY);
            }
            return epochMicros;
        }
        if (type instanceof Types.TimeType) {
            return Math.multiplyExact((Long) value, PICOSECONDS_PER_MICROSECOND);
        }
        if (type instanceof Types.FloatType) {
            return Float.floatToIntBits((Float) value);
        }
        if (type instanceof Types.IntegerType || type instanceof Types.DateType) {
            return ((Integer) value).longValue();
        }
        if (type instanceof Types.DecimalType) {
            Types.DecimalType icebergDecimalType = (Types.DecimalType) type;
            DecimalType trinoDecimalType = DecimalType.createDecimalType(icebergDecimalType.precision(), icebergDecimalType.scale());
            if (isShortDecimal(trinoDecimalType)) {
                return Decimals.encodeShortScaledValue((BigDecimal) value, trinoDecimalType.getScale());
            }
            return Decimals.encodeScaledValue((BigDecimal) value, trinoDecimalType.getScale());
        }
        // TODO implement explicit conversion for all supported types
        return value;
    }
}
