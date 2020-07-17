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
package io.prestosql.plugin.hive.orc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.orc.OrcReaderOptions;
import io.prestosql.orc.OrcWriterOptions;
import io.prestosql.plugin.hive.AbstractTestHiveFileFormats;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveCompressionCodec;
import io.prestosql.plugin.hive.HiveConfig;
import io.prestosql.plugin.hive.HivePageSourceProvider;
import io.prestosql.plugin.hive.HivePartitionKey;
import io.prestosql.plugin.hive.NodeVersion;
import io.prestosql.plugin.hive.TableToPartitionMapping;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.FileSplit;
import org.joda.time.DateTimeZone;
import org.testng.annotations.Test;

import java.io.File;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.plugin.hive.HiveStorageFormat.ORC;
import static io.prestosql.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.prestosql.plugin.hive.HiveTestUtils.TYPE_MANAGER;
import static io.prestosql.plugin.hive.HiveTestUtils.getHiveSession;
import static io.prestosql.plugin.hive.parquet.ParquetTester.HIVE_STORAGE_TIME_ZONE;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.testing.StructuralTestUtil.rowBlockOf;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.FILE_INPUT_FORMAT;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_LIB;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestOrcPredicates
        extends AbstractTestHiveFileFormats
{
    private static final int NUM_ROWS = 50000;
    private static final FileFormatDataSourceStats STATS = new FileFormatDataSourceStats();

    // Prepare test columns
    private static final TestColumn columnPrimitiveInteger = new TestColumn("column_primitive_integer", javaIntObjectInspector, 3, 3);
    private static final TestColumn columnStruct = new TestColumn(
            "column1_struct",
            getStandardStructObjectInspector(ImmutableList.of("field0", "field1"), ImmutableList.of(javaLongObjectInspector, javaLongObjectInspector)),
            new Long[] {4L, 5L},
            rowBlockOf(ImmutableList.of(BIGINT, BIGINT), 4L, 5L));
    private static final TestColumn columnPrimitiveBigInt = new TestColumn("column_primitive_bigint", javaLongObjectInspector, 6L, 6L);

    @Test
    public void testOrcPredicates()
            throws Exception
    {
        testOrcPredicates(getHiveSession(new HiveConfig(), new OrcReaderConfig().setUseColumnNames(true)));
        testOrcPredicates(getHiveSession(new HiveConfig(), new OrcReaderConfig()));
    }

    private void testOrcPredicates(ConnectorSession session)
            throws Exception
    {
        List<TestColumn> columnsToWrite = ImmutableList.of(columnPrimitiveInteger, columnStruct, columnPrimitiveBigInt);

        File file = File.createTempFile("test", "orc_predicate");
        file.delete();
        try {
            // Write data
            OrcFileWriterFactory writerFactory = new OrcFileWriterFactory(HDFS_ENVIRONMENT, TYPE_MANAGER, new NodeVersion("test"), HIVE_STORAGE_TIME_ZONE, false, STATS, new OrcWriterOptions());
            FileSplit split = createTestFile(file.getAbsolutePath(), ORC, HiveCompressionCodec.NONE, columnsToWrite, session, NUM_ROWS, writerFactory);

            TupleDomain<TestColumn> testingPredicate;

            // Verify predicates on base column
            List<TestColumn> columnsToRead = columnsToWrite;
            // All rows returned for a satisfying predicate
            testingPredicate = TupleDomain.withColumnDomains(ImmutableMap.of(columnPrimitiveBigInt, Domain.singleValue(BIGINT, 6L)));
            assertFilteredRows(testingPredicate, columnsToRead, session, split, NUM_ROWS);
            // No rows returned for a mismatched predicate
            testingPredicate = TupleDomain.withColumnDomains(ImmutableMap.of(columnPrimitiveBigInt, Domain.singleValue(BIGINT, 1L)));
            assertFilteredRows(testingPredicate, columnsToRead, session, split, 0);

            // Verify predicates on projected column
            TestColumn projectedColumn = new TestColumn(
                    columnStruct.getBaseName(),
                    columnStruct.getBaseObjectInspector(),
                    ImmutableList.of("field1"),
                    ImmutableList.of(1),
                    javaLongObjectInspector,
                    5L,
                    5L,
                    false);

            columnsToRead = ImmutableList.of(columnPrimitiveBigInt, projectedColumn);
            // All rows returned for a satisfying predicate
            testingPredicate = TupleDomain.withColumnDomains(ImmutableMap.of(projectedColumn, Domain.singleValue(BIGINT, 5L)));
            assertFilteredRows(testingPredicate, columnsToRead, session, split, NUM_ROWS);
            // No rows returned for a mismatched predicate
            testingPredicate = TupleDomain.withColumnDomains(ImmutableMap.of(projectedColumn, Domain.singleValue(BIGINT, 6L)));
            assertFilteredRows(testingPredicate, columnsToRead, session, split, 0);
        }
        finally {
            file.delete();
        }
    }

    private void assertFilteredRows(
            TupleDomain<TestColumn> effectivePredicate,
            List<TestColumn> columnsToRead,
            ConnectorSession session,
            FileSplit split,
            int expectedRows)
    {
        ConnectorPageSource pageSource = createPageSource(effectivePredicate, columnsToRead, session, split);

        int filteredRows = 0;
        while (!pageSource.isFinished()) {
            Page page = pageSource.getNextPage();
            if (page != null) {
                filteredRows += page.getPositionCount();
            }
        }

        assertEquals(filteredRows, expectedRows);
    }

    private ConnectorPageSource createPageSource(
            TupleDomain<TestColumn> effectivePredicate,
            List<TestColumn> columnsToRead,
            ConnectorSession session,
            FileSplit split)
    {
        OrcPageSourceFactory readerFactory = new OrcPageSourceFactory(new OrcReaderOptions(), HDFS_ENVIRONMENT, STATS);

        Properties splitProperties = new Properties();
        splitProperties.setProperty(FILE_INPUT_FORMAT, ORC.getInputFormat());
        splitProperties.setProperty(SERIALIZATION_LIB, ORC.getSerDe());

        // Use full columns in split properties
        ImmutableList.Builder<String> splitPropertiesColumnNames = ImmutableList.builder();
        ImmutableList.Builder<String> splitPropertiesColumnTypes = ImmutableList.builder();
        Set<String> baseColumnNames = new HashSet<>();
        for (TestColumn columnToRead : columnsToRead) {
            String name = columnToRead.getBaseName();
            if (!baseColumnNames.contains(name) && !columnToRead.isPartitionKey()) {
                baseColumnNames.add(name);
                splitPropertiesColumnNames.add(name);
                splitPropertiesColumnTypes.add(columnToRead.getBaseObjectInspector().getTypeName());
            }
        }

        splitProperties.setProperty("columns", splitPropertiesColumnNames.build().stream().collect(Collectors.joining(",")));
        splitProperties.setProperty("columns.types", splitPropertiesColumnTypes.build().stream().collect(Collectors.joining(",")));

        List<HivePartitionKey> partitionKeys = columnsToRead.stream()
                .filter(TestColumn::isPartitionKey)
                .map(input -> new HivePartitionKey(input.getName(), (String) input.getWriteValue()))
                .collect(toList());

        String partitionName = String.join("/", partitionKeys.stream()
                .map(partitionKey -> format("%s=%s", partitionKey.getName(), partitionKey.getValue()))
                .collect(toImmutableList()));

        List<HiveColumnHandle> columnHandles = getColumnHandles(columnsToRead);

        TupleDomain<HiveColumnHandle> predicate = effectivePredicate.transform(testColumn -> {
            Optional<HiveColumnHandle> handle = columnHandles.stream()
                    .filter(column -> testColumn.getName().equals(column.getName()))
                    .findFirst();

            checkState(handle.isPresent(), "Predicate on invalid column");
            return handle.get();
        });

        Optional<ConnectorPageSource> pageSource = HivePageSourceProvider.createHivePageSource(
                ImmutableSet.of(readerFactory),
                ImmutableSet.of(),
                new Configuration(false),
                session,
                split.getPath(),
                OptionalInt.empty(),
                split.getStart(),
                split.getLength(),
                split.getLength(),
                Instant.now().toEpochMilli(),
                splitProperties,
                predicate,
                columnHandles,
                partitionName,
                partitionKeys,
                DateTimeZone.getDefault(),
                TYPE_MANAGER,
                TableToPartitionMapping.empty(),
                Optional.empty(),
                false,
                Optional.empty());

        assertTrue(pageSource.isPresent());
        return pageSource.get();
    }
}
