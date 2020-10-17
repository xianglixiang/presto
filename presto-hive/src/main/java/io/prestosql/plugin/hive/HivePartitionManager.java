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
package io.prestosql.plugin.hive;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.plugin.hive.util.HiveBucketing.HiveBucketFilter;
import io.prestosql.plugin.hive.util.Optionals;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.Constraint;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import org.apache.hadoop.hive.common.FileUtils;

import javax.inject.Inject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_EXCEEDED_PARTITION_LIMIT;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.computePartitionKeyFilter;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.toPartitionName;
import static io.prestosql.plugin.hive.util.HiveBucketing.getHiveBucketFilter;
import static io.prestosql.plugin.hive.util.HiveUtil.parsePartitionValue;
import static io.prestosql.spi.predicate.TupleDomain.none;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

public class HivePartitionManager
{
    private final int maxPartitions;
    private final int domainCompactionThreshold;

    @Inject
    public HivePartitionManager(HiveConfig hiveConfig)
    {
        this(
                hiveConfig.getMaxPartitionsPerScan(),
                hiveConfig.getDomainCompactionThreshold());
    }

    public HivePartitionManager(
            int maxPartitions,
            int domainCompactionThreshold)
    {
        checkArgument(maxPartitions >= 1, "maxPartitions must be at least 1");
        this.maxPartitions = maxPartitions;
        checkArgument(domainCompactionThreshold >= 1, "domainCompactionThreshold must be at least 1");
        this.domainCompactionThreshold = domainCompactionThreshold;
    }

    public HivePartitionResult getPartitions(SemiTransactionalHiveMetastore metastore, HiveIdentity identity, ConnectorTableHandle tableHandle, Constraint constraint)
    {
        HiveTableHandle hiveTableHandle = (HiveTableHandle) tableHandle;
        TupleDomain<ColumnHandle> effectivePredicate = constraint.getSummary()
                .intersect(hiveTableHandle.getEnforcedConstraint());

        SchemaTableName tableName = hiveTableHandle.getSchemaTableName();
        Optional<HiveBucketHandle> hiveBucketHandle = hiveTableHandle.getBucketHandle();
        List<HiveColumnHandle> partitionColumns = hiveTableHandle.getPartitionColumns();

        if (effectivePredicate.isNone()) {
            return new HivePartitionResult(partitionColumns, ImmutableList.of(), none(), none(), none(), hiveBucketHandle, Optional.empty());
        }

        Table table = metastore.getTable(identity, tableName.getSchemaName(), tableName.getTableName())
                .orElseThrow(() -> new TableNotFoundException(tableName));

        Optional<HiveBucketFilter> bucketFilter = getHiveBucketFilter(table, effectivePredicate);
        TupleDomain<HiveColumnHandle> compactEffectivePredicate = effectivePredicate
                .transform(HiveColumnHandle.class::cast)
                .simplify(domainCompactionThreshold);

        if (partitionColumns.isEmpty()) {
            return new HivePartitionResult(
                    partitionColumns,
                    ImmutableList.of(new HivePartition(tableName)),
                    compactEffectivePredicate,
                    effectivePredicate,
                    TupleDomain.all(),
                    hiveBucketHandle,
                    bucketFilter);
        }

        List<Type> partitionTypes = partitionColumns.stream()
                .map(HiveColumnHandle::getType)
                .collect(toList());

        Iterable<HivePartition> partitionsIterable;
        Predicate<Map<ColumnHandle, NullableValue>> predicate = constraint.predicate().orElse(value -> true);
        if (hiveTableHandle.getPartitions().isPresent()) {
            partitionsIterable = hiveTableHandle.getPartitions().get().stream()
                    .filter(partition -> partitionMatches(partitionColumns, effectivePredicate, predicate, partition))
                    .collect(toImmutableList());
        }
        else {
            List<String> partitionNames = getFilteredPartitionNames(metastore, identity, tableName, partitionColumns, compactEffectivePredicate);
            partitionsIterable = () -> partitionNames.stream()
                    // Apply extra filters which could not be done by getFilteredPartitionNames
                    .map(partitionName -> parseValuesAndFilterPartition(tableName, partitionName, partitionColumns, partitionTypes, effectivePredicate, predicate))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .iterator();
        }

        // All partition key domains will be fully evaluated, so we don't need to include those
        TupleDomain<ColumnHandle> remainingTupleDomain = effectivePredicate.filter((column, domain) -> !partitionColumns.contains(column));
        TupleDomain<ColumnHandle> enforcedTupleDomain = effectivePredicate.filter((column, domain) -> partitionColumns.contains(column));
        return new HivePartitionResult(partitionColumns, partitionsIterable, compactEffectivePredicate, remainingTupleDomain, enforcedTupleDomain, hiveBucketHandle, bucketFilter);
    }

    public HivePartitionResult getPartitions(ConnectorTableHandle tableHandle, List<List<String>> partitionValuesList)
    {
        HiveTableHandle hiveTableHandle = (HiveTableHandle) tableHandle;
        SchemaTableName tableName = hiveTableHandle.getSchemaTableName();
        List<HiveColumnHandle> partitionColumns = hiveTableHandle.getPartitionColumns();
        Optional<HiveBucketHandle> bucketHandle = hiveTableHandle.getBucketHandle();

        List<String> partitionColumnNames = partitionColumns.stream()
                .map(HiveColumnHandle::getName)
                .collect(toImmutableList());

        List<Type> partitionColumnTypes = partitionColumns.stream()
                .map(HiveColumnHandle::getType)
                .collect(toImmutableList());

        List<HivePartition> partitionList = partitionValuesList.stream()
                .map(partitionValues -> toPartitionName(partitionColumnNames, partitionValues))
                .map(partitionName -> parseValuesAndFilterPartition(tableName, partitionName, partitionColumns, partitionColumnTypes, TupleDomain.all(), value -> true))
                .map(partition -> partition.orElseThrow(() -> new VerifyException("partition must exist")))
                .collect(toImmutableList());

        return new HivePartitionResult(partitionColumns, partitionList, TupleDomain.all(), TupleDomain.all(), TupleDomain.all(), bucketHandle, Optional.empty());
    }

    public List<HivePartition> getPartitionsAsList(HivePartitionResult partitionResult)
    {
        ImmutableList.Builder<HivePartition> partitionList = ImmutableList.builder();
        int count = 0;
        Iterator<HivePartition> iterator = partitionResult.getPartitions();
        while (iterator.hasNext()) {
            HivePartition partition = iterator.next();
            if (count == maxPartitions) {
                throw new PrestoException(HIVE_EXCEEDED_PARTITION_LIMIT, format(
                        "Query over table '%s' can potentially read more than %s partitions",
                        partition.getTableName(),
                        maxPartitions));
            }
            partitionList.add(partition);
            count++;
        }
        return partitionList.build();
    }

    public HiveTableHandle applyPartitionResult(HiveTableHandle handle, HivePartitionResult partitions, Optional<Set<ColumnHandle>> columns)
    {
        return new HiveTableHandle(
                handle.getSchemaName(),
                handle.getTableName(),
                handle.getTableParameters(),
                ImmutableList.copyOf(partitions.getPartitionColumns()),
                Optional.of(getPartitionsAsList(partitions)),
                partitions.getCompactEffectivePredicate(),
                partitions.getEnforcedConstraint(),
                partitions.getBucketHandle(),
                partitions.getBucketFilter(),
                handle.getAnalyzePartitionValues(),
                handle.getAnalyzeColumnNames(),
                Optionals.combine(handle.getConstraintColumns(), columns,
                        Sets::union));
    }

    public List<HivePartition> getOrLoadPartitions(SemiTransactionalHiveMetastore metastore, HiveIdentity identity, HiveTableHandle table)
    {
        return table.getPartitions().orElseGet(() ->
                getPartitionsAsList(getPartitions(metastore, identity, table, new Constraint(table.getEnforcedConstraint()))));
    }

    private Optional<HivePartition> parseValuesAndFilterPartition(
            SchemaTableName tableName,
            String partitionId,
            List<HiveColumnHandle> partitionColumns,
            List<Type> partitionColumnTypes,
            TupleDomain<ColumnHandle> constraintSummary,
            Predicate<Map<ColumnHandle, NullableValue>> constraint)
    {
        HivePartition partition = parsePartition(tableName, partitionId, partitionColumns, partitionColumnTypes);

        if (partitionMatches(partitionColumns, constraintSummary, constraint, partition)) {
            return Optional.of(partition);
        }
        return Optional.empty();
    }

    private boolean partitionMatches(List<HiveColumnHandle> partitionColumns, TupleDomain<ColumnHandle> constraintSummary, Predicate<Map<ColumnHandle, NullableValue>> constraint, HivePartition partition)
    {
        return partitionMatches(partitionColumns, constraintSummary, partition) && constraint.test(partition.getKeys());
    }

    public static boolean partitionMatches(List<HiveColumnHandle> partitionColumns, TupleDomain<ColumnHandle> constraintSummary, HivePartition partition)
    {
        if (constraintSummary.isNone()) {
            return false;
        }
        Map<ColumnHandle, Domain> domains = constraintSummary.getDomains().get();
        for (HiveColumnHandle column : partitionColumns) {
            NullableValue value = partition.getKeys().get(column);
            Domain allowedDomain = domains.get(column);
            if (allowedDomain != null && !allowedDomain.includesNullableValue(value.getValue())) {
                return false;
            }
        }
        return true;
    }

    private List<String> getFilteredPartitionNames(SemiTransactionalHiveMetastore metastore, HiveIdentity identity, SchemaTableName tableName, List<HiveColumnHandle> partitionKeys, TupleDomain<HiveColumnHandle> effectivePredicate)
    {
        List<String> columnNames = partitionKeys.stream()
                .map(HiveColumnHandle::getName)
                .collect(toImmutableList());
        TupleDomain<String> partitionKeysFilter = computePartitionKeyFilter(partitionKeys, effectivePredicate);
        // fetch the partition names
        return metastore.getPartitionNamesByFilter(identity, tableName.getSchemaName(), tableName.getTableName(), columnNames, partitionKeysFilter)
                .orElseThrow(() -> new TableNotFoundException(tableName));
    }

    public static HivePartition parsePartition(
            SchemaTableName tableName,
            String partitionName,
            List<HiveColumnHandle> partitionColumns,
            List<Type> partitionColumnTypes)
    {
        List<String> partitionValues = extractPartitionValues(partitionName);
        ImmutableMap.Builder<ColumnHandle, NullableValue> builder = ImmutableMap.builder();
        for (int i = 0; i < partitionColumns.size(); i++) {
            HiveColumnHandle column = partitionColumns.get(i);
            NullableValue parsedValue = parsePartitionValue(partitionName, partitionValues.get(i), partitionColumnTypes.get(i));
            builder.put(column, parsedValue);
        }
        Map<ColumnHandle, NullableValue> values = builder.build();
        return new HivePartition(tableName, partitionName, values);
    }

    public static List<String> extractPartitionValues(String partitionName)
    {
        ImmutableList.Builder<String> values = ImmutableList.builder();

        boolean inKey = true;
        int valueStart = -1;
        for (int i = 0; i < partitionName.length(); i++) {
            char current = partitionName.charAt(i);
            if (inKey) {
                checkArgument(current != '/', "Invalid partition spec: %s", partitionName);
                if (current == '=') {
                    inKey = false;
                    valueStart = i + 1;
                }
            }
            else if (current == '/') {
                checkArgument(valueStart != -1, "Invalid partition spec: %s", partitionName);
                values.add(FileUtils.unescapePathName(partitionName.substring(valueStart, i)));
                inKey = true;
                valueStart = -1;
            }
        }
        checkArgument(!inKey, "Invalid partition spec: %s", partitionName);
        values.add(FileUtils.unescapePathName(partitionName.substring(valueStart)));

        return values.build();
    }
}
