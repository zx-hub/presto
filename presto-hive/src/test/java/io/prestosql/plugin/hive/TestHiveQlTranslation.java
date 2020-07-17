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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import io.prestosql.spi.PrestoException;
import io.prestosql.sql.parser.ParsingOptions;
import io.prestosql.sql.parser.SqlParser;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_VIEW_TRANSLATION_ERROR;
import static io.prestosql.plugin.hive.HiveQlToPrestoTranslator.translateHiveViewToPresto;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Collections.nCopies;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestHiveQlTranslation
{
    private final SqlParser parser = new SqlParser();

    // Map Hive names to Presto names
    private static Map<String, String> simpleColumnNames =
            ImmutableMap.<String, String>builder()
                    // simple literals
                    .put(
                            "unquoted",
                            "unquoted")
                    .put(
                            "`backquoted`",
                            "\"backquoted\"")
                    .put(
                            "`sometable`.`backquoted`",
                            "\"sometable\".\"backquoted\"")
                    .put(
                            "'single quoted'",
                            "'single quoted'")
                    .put(
                            "\"double quoted\"",
                            "'double quoted'")
                    // empty strings
                    .put("''", "''")
                    .put("\"\"", "''")
                    // just quotes
                    .put("'\\''", "''''")
                    .put("\"\\\"\"", "'\"'")
                    .build();

    private static Map<String, String> extendedColumnNames =
            ImmutableMap.<String, String>builder()
                    .putAll(simpleColumnNames)
                    .put(
                            "`id: ``back`",
                            "\"id: `back\"")
                    .put(
                            "`id: \"double`",
                            "\"id: \"\"double\"")
                    .put(
                            "`id: \"\"two double`",
                            "\"id: \"\"\"\"two double\"")
                    .put(
                            "`id: two back`````",
                            "\"id: two back``\"")
                    .put(
                            "'single: \"double'",
                            "'single: \"double'")
                    .put(
                            "'single: \\'single'",
                            "'single: ''single'")
                    .put(
                            "'single: \\'\\'two singles'",
                            "'single: ''''two singles'")
                    .put(
                            "\"double: double\\\"\"",
                            "'double: double\"'")
                    .put(
                            "\"double: single'\"",
                            "'double: single'''")
                    .put(
                            "\"double: two singles''\"",
                            "'double: two singles'''''")
                    .build();

    /** Prepare all combinations of {@code n} of the given columns. */
    private static Iterator<Object[]> getNColumns(int n, Map<String, String> columns)
    {
        Stream<String> hiveNames =
                Sets.cartesianProduct(nCopies(n, columns.keySet())).stream()
                        .map(names -> join(", ", names));

        Stream<String> prestoNames =
                Lists.cartesianProduct(nCopies(n, List.copyOf(columns.values()))).stream()
                        .map(names -> join(", ", names));

        return Streams.zip(hiveNames, prestoNames, (h, p) -> new Object[]{h, p}).iterator();
    }

    @DataProvider(name = "simple_hive_translation_columns")
    public Iterator<Object[]> getSimpleColumns()
    {
        return Iterators.concat(
                getNColumns(1, simpleColumnNames),
                getNColumns(3, simpleColumnNames));
    }

    @DataProvider(name = "extended_hive_translation_columns")
    public Iterator<Object[]> getExtendedColumns()
    {
        return Iterators.concat(
                getNColumns(1, extendedColumnNames),
                getNColumns(2, extendedColumnNames));
    }

    @Test
    public void testIdentifiers()
    {
        assertTranslation(
                "SELECT * FROM nation",
                "SELECT * FROM nation");
        assertTranslation(
                "SELECT * FROM `nation`",
                "SELECT * FROM \"nation\"");
        assertTranslation(
                "SELECT `nation`.`nationkey` FROM `nation`",
                "SELECT \"nation\".\"nationkey\" FROM \"nation\"");
        assertTranslation(
                "SELECT * FROM `it's a table`",
                "SELECT * FROM \"it's a table\"");
    }

    @Test
    public void testNumberLiterals()
    {
        assertTranslation(
                "SELECT 1",
                "SELECT 1");
    }

    @Test
    public void testStringLiterals()
    {
        assertTranslation(
                "SELECT '`'",
                "SELECT '`'");
        assertTranslation(
                "SELECT 'it\\'s an \"apple\"'",
                "SELECT 'it''s an \"apple\"'");
        assertTranslation(
                "SELECT \"it's an \\\"apple\\\"\"",
                "SELECT 'it''s an \"apple\"'");
        assertTranslation(
                "SELECT \"`\"",
                "SELECT '`'");
        assertTranslation(
                "SELECT '\"'",
                "SELECT '\"'");
        assertTranslation(
                "SELECT \"'\"",
                "SELECT ''''");
        assertTranslation(
                "SELECT '\\'`'",
                "SELECT '''`'");
        assertTranslation(
                "SELECT '\\\\\\''",
                "SELECT '\\'''");
        assertTranslation(
                "SELECT \"\\'`\"",
                "SELECT '''`'");
    }

    @Test
    public void testStringLiteralsWithNewLine()
    {
        assertTranslation(
                "SELECT \"'\n'\"",
                "SELECT '''\n'''");
        assertTranslation(
                "SELECT '\\'\n`'",
                "SELECT '''\n`'");
        assertTranslation(
                "SELECT \"\\\n'`\"",
                "SELECT '\n''`'");
    }

    @Test
    public void testPredicates()
    {
        assertTranslation(
                "SELECT \"'\" = \"'\" OR false",
                "SELECT '''' = '''' OR false");
    }

    @Test(dataProvider = "simple_hive_translation_columns")
    public void testSimpleColumns(String hiveColumn, String prestoColumn)
    {
        assertTranslation(
                format("SELECT %s FROM sometable", hiveColumn),
                format("SELECT %s FROM sometable", prestoColumn));
    }

    @Test(dataProvider = "extended_hive_translation_columns")
    public void testExtendedColumns(String hiveColumn, String prestoColumn)
    {
        assertTranslation(
                format("SELECT %s FROM sometable", hiveColumn),
                format("SELECT %s FROM sometable", prestoColumn));
    }

    @Test
    public void testEarlyEndOfInput()
    {
        String inString = "unexpected end of input in string";
        String inIdentifier = "unexpected end of input in identifier";
        assertViewTranslationError("SELECT \"open", inString);
        assertViewTranslationError("SELECT 'open", inString);
        assertViewTranslationError("SELECT `open", inIdentifier);
        // With an escaped quote
        assertViewTranslationError("SELECT \"open\\\"", inString);
        assertViewTranslationError("SELECT 'open\\'", inString);
        assertViewTranslationError("SELECT `open``", inIdentifier);
    }

    @Test
    public void testStringEscapes()
    {
        assertTranslation(
                "SELECT '\\n' FROM sometable",
                "SELECT '\n' FROM sometable");
        assertTranslation(
                "SELECT 'abc\\u03B5xyz' FROM sometable",
                "SELECT 'abc\u03B5xyz' FROM sometable"); // that's epsilon
    }

    private void assertTranslation(String hiveSql, String expectedPrestoSql)
    {
        String actualPrestoSql = translateHiveViewToPresto(hiveSql);
        assertEquals(actualPrestoSql, expectedPrestoSql);
        assertPrestoSqlIsParsable(expectedPrestoSql);
        assertPrestoSqlIsParsable(actualPrestoSql);
    }

    private void assertPrestoSqlIsParsable(String actualPrestoSql)
    {
        parser.createStatement(actualPrestoSql, new ParsingOptions());
    }

    private void assertViewTranslationError(String badHiveQl, String expectMessage)
    {
        try {
            translateHiveViewToPresto(badHiveQl);
            fail("Expected Hive translation to throw an exception");
        }
        catch (PrestoException e) {
            assertEquals(e.getErrorCode(), HIVE_VIEW_TRANSLATION_ERROR.toErrorCode());
            assertTrue(e.getRawMessage().contains(expectMessage));
        }
    }
}
