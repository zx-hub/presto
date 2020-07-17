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
package io.prestosql.tests.hive;

import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.assertions.QueryAssert.Row;
import io.prestosql.tempto.query.QueryResult;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.STORAGE_FORMATS;
import static io.prestosql.tests.utils.QueryExecutors.onHive;
import static java.lang.String.format;

public class TestCsv
        extends ProductTest
{
    private static final String TPCH_SCHEMA = "tiny";

    @Test(groups = STORAGE_FORMATS)
    public void testInsertIntoCsvTable()
    {
        testInsertIntoCsvTable("storage_formats_test_insert_into_csv", "");
    }

    @Test(groups = STORAGE_FORMATS)
    public void testInsertIntoCsvTableWithCustomProperties()
    {
        testInsertIntoCsvTable("storage_formats_test_insert_into_csv_with_custom_properties", ", csv_escape = 'e', csv_separator='s', csv_quote='q'");
    }

    private void testInsertIntoCsvTable(String tableName, String additionalTableProperties)
    {
        query("DROP TABLE IF EXISTS " + tableName);

        query(format(
                "CREATE TABLE %s(" +
                        "   linestatus    varchar," +
                        "   shipinstruct  varchar," +
                        "   shipmode      varchar," +
                        "   comment       varchar," +
                        "   returnflag    varchar" +
                        ") WITH (format='CSV' %s)",
                tableName, additionalTableProperties));

        query(format("INSERT INTO %s SELECT linestatus, shipinstruct, shipmode, comment, returnflag FROM tpch.%s.lineitem", tableName, TPCH_SCHEMA));

        assertSelect("select max(linestatus), max(shipinstruct), max(shipmode) from %s", tableName);

        query("DROP TABLE " + tableName);
    }

    @Test(groups = STORAGE_FORMATS)
    public void testCreateCsvTableAs()
    {
        testCreateCsvTableAs("");
    }

    @Test(groups = STORAGE_FORMATS)
    public void testCreateCsvTableAsWithCustomProperties()
    {
        testCreateCsvTableAs(", csv_escape = 'e', csv_separator = 's', csv_quote = 'q'");
    }

    private void testCreateCsvTableAs(String additionalParameters)
    {
        String tableName = "test_csv_table";
        query("DROP TABLE IF EXISTS " + tableName);

        query(format(
                "CREATE TABLE %s WITH (format='CSV' %s) AS " +
                        "SELECT " +
                        "cast(linestatus AS varchar) AS linestatus, cast(shipmode AS varchar) AS shipmode, cast(returnflag AS varchar) AS returnflag " +
                        "FROM tpch.tiny.lineitem",
                tableName,
                additionalParameters));

        assertSelect("select max(linestatus), max(shipmode), count(returnflag) from %s", tableName);

        query("DROP TABLE " + tableName);
    }

    @Test(groups = STORAGE_FORMATS)
    public void testInsertIntoPartitionedCsvTable()
    {
        testInsertIntoPartitionedCsvTable("test_partitioned_csv_table", "");
    }

    @Test(groups = STORAGE_FORMATS)
    public void testInsertIntoPartitionedCsvTableWithCustomProperties()
    {
        testInsertIntoPartitionedCsvTable("test_partitioned_csv_table_with_custom_parameters", ", csv_escape = 'e', csv_separator = 's', csv_quote = 'q'");
    }

    private void testInsertIntoPartitionedCsvTable(String tableName, String additionalParameters)
    {
        query("DROP TABLE IF EXISTS " + tableName);

        query(format(
                "CREATE TABLE %s(" +
                        "   linestatus    varchar," +
                        "   shipinstruct  varchar," +
                        "   shipmode      varchar," +
                        "   comment       varchar," +
                        "   returnflag    varchar," +
                        "   suppkey       bigint" +
                        ") WITH (format='CSV' %s, partitioned_by = ARRAY['suppkey'])",
                tableName,
                additionalParameters));

        query(format(
                "INSERT INTO %s " +
                        "SELECT " +
                        "linestatus, shipinstruct, shipmode, comment, returnflag, suppkey " +
                        "FROM tpch.%s.lineitem", tableName, TPCH_SCHEMA));

        assertSelect("select max(linestatus), max(shipinstruct), max(shipmode), max(suppkey) from %s", tableName);

        query("DROP TABLE " + tableName);
    }

    @Test(groups = STORAGE_FORMATS)
    public void testCreatePartitionedCsvTableAs()
    {
        testCreatePartitionedCsvTableAs("storage_formats_test_create_table_as_select_partitioned_csv", "");
    }

    @Test(groups = STORAGE_FORMATS)
    public void testCreatePartitionedCsvTableAsWithCustomParamters()
    {
        testCreatePartitionedCsvTableAs(
                "storage_formats_test_create_table_as_select_partitioned_csv_with_custom_parameters",
                ", csv_escape = 'e', csv_separator='s', csv_quote='q'");
    }

    private void testCreatePartitionedCsvTableAs(String tableName, String additionalParameters)
    {
        query("DROP TABLE IF EXISTS " + tableName);

        query(format(
                "CREATE TABLE %s WITH (format='CSV', partitioned_by = ARRAY['suppkey'] %s) AS " +
                        "SELECT cast(shipmode AS varchar) AS shipmode, cast(comment AS varchar) AS comment, suppkey FROM tpch.tiny.lineitem",
                tableName,
                additionalParameters));

        assertSelect("select max(shipmode), max(comment), sum(suppkey) from %s", tableName);

        query("DROP TABLE " + tableName);
    }

    private static void assertSelect(String query, String tableName)
    {
        QueryResult expected = query(format(query, "tpch." + TPCH_SCHEMA + ".lineitem"));
        List<Row> expectedRows = expected.rows().stream()
                .map((columns) -> row(columns.toArray()))
                .collect(toImmutableList());
        QueryResult actual = query(format(query, tableName));
        assertThat(actual)
                .hasColumns(expected.getColumnTypes())
                .containsOnly(expectedRows);
    }

    @Test(groups = STORAGE_FORMATS)
    public void testReadCsvTableWithMultiCharProperties()
    {
        String tableName = "storage_formats_test_read_csv_table_with_multi_char_properties";
        onHive().executeQuery(format("DROP TABLE IF EXISTS %s", tableName));
        onHive().executeQuery(format(
                "CREATE TABLE %s(" +
                        "   a  string," +
                        "   b  string," +
                        "   c  string" +
                        ") ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde' " +
                        "WITH SERDEPROPERTIES ('escapeChar'='ee','separatorChar'='ss','quoteChar'='qq') " +
                        "STORED AS " +
                        "INPUTFORMAT 'org.apache.hadoop.mapred.TextInputFormat' " +
                        "OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'",
                tableName));

        onHive().executeQuery(format(
                "INSERT INTO %s(a, b, c) VALUES " +
                        "('1', 'a', 'A'), " +
                        "('2', 'b', 'B'), " +
                        "('3', 'c', 'C')",
                tableName));

        assertThat(query(format("SELECT * FROM %s", tableName)))
                .containsOnly(
                        row("1", "a", "A"),
                        row("2", "b", "B"),
                        row("3", "c", "C"));
        onHive().executeQuery(format("DROP TABLE %s", tableName));
    }

    @Test(groups = STORAGE_FORMATS)
    public void testWriteCsvTableWithMultiCharProperties()
    {
        String tableName = "storage_formats_test_write_csv_table_with_multi_char_properties";
        onHive().executeQuery(format("DROP TABLE IF EXISTS %s", tableName));
        onHive().executeQuery(format(
                "CREATE TABLE %s(" +
                        "   a  string," +
                        "   b  string," +
                        "   c  string" +
                        ") ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde' " +
                        "WITH SERDEPROPERTIES ('escapeChar'='ee','separatorChar'='ss','quoteChar'='qq') " +
                        "STORED AS " +
                        "INPUTFORMAT 'org.apache.hadoop.mapred.TextInputFormat' " +
                        "OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'",
                tableName));

        query(format(
                "INSERT INTO %s(a, b, c) VALUES " +
                        "('1', 'a', 'A'), " +
                        "('2', 'b', 'B'), " +
                        "('3', 'c', 'C')",
                tableName));
        assertThat(query(format("SELECT * FROM %s", tableName)))
                .containsOnly(
                        row("1", "a", "A"),
                        row("2", "b", "B"),
                        row("3", "c", "C"));
        onHive().executeQuery(format("DROP TABLE %s", tableName));
    }
}
