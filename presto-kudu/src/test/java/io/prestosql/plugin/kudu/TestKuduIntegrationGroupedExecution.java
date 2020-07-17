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
package io.prestosql.plugin.kudu;

import io.prestosql.Session;
import io.prestosql.cost.StatsAndCosts;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.Plan;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.testing.AbstractTestQueryFramework;
import io.prestosql.testing.DistributedQueryRunner;
import io.prestosql.testing.QueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.function.Consumer;

import static io.prestosql.SystemSessionProperties.COLOCATED_JOIN;
import static io.prestosql.SystemSessionProperties.CONCURRENT_LIFESPANS_PER_NODE;
import static io.prestosql.SystemSessionProperties.DYNAMIC_SCHEDULE_FOR_GROUPED_EXECUTION;
import static io.prestosql.SystemSessionProperties.ENABLE_DYNAMIC_FILTERING;
import static io.prestosql.SystemSessionProperties.GROUPED_EXECUTION;
import static io.prestosql.plugin.kudu.KuduQueryRunnerFactory.createKuduQueryRunner;
import static io.prestosql.plugin.kudu.KuduQueryRunnerFactory.createSession;
import static io.prestosql.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static io.prestosql.sql.planner.planprinter.PlanPrinter.textLogicalPlan;
import static java.lang.String.format;

public class TestKuduIntegrationGroupedExecution
        extends AbstractTestQueryFramework
{
    private static final String SCHEMA_KUDU = "kudu";
    private static final String KUDU_GROUPED_EXECUTION = "grouped_execution";
    private TestingKuduServer kuduServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        kuduServer = new TestingKuduServer();
        Session groupedExecutionSessionProperties = Session.builder(createSession("test_grouped_execution"))
                .setSystemProperty(COLOCATED_JOIN, "true")
                .setSystemProperty(GROUPED_EXECUTION, "true")
                .setSystemProperty(CONCURRENT_LIFESPANS_PER_NODE, "1")
                .setSystemProperty(DYNAMIC_SCHEDULE_FOR_GROUPED_EXECUTION, "false")
                .setSystemProperty(ENABLE_DYNAMIC_FILTERING, "false")
                .setCatalogSessionProperty(SCHEMA_KUDU, KUDU_GROUPED_EXECUTION, "true")
                .build();
        return createKuduQueryRunner(kuduServer, groupedExecutionSessionProperties);
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        kuduServer.close();
    }

    @Test
    public void testGroupedExecutionJoin()
    {
        assertUpdate("CREATE TABLE IF NOT EXISTS test_grouped_execution_t1 (" +
                "key1 INT WITH (primary_key=true), " +
                "key2 INT WITH (primary_key=true), " +
                "attr1 INT" +
                ") WITH (" +
                " partition_by_hash_columns = ARRAY['key1'], " +
                " partition_by_hash_buckets = 2" +
                ")");

        assertUpdate("CREATE TABLE IF NOT EXISTS test_grouped_execution_t2 (" +
                "key1 INT WITH (primary_key=true), " +
                "key2 INT WITH (primary_key=true), " +
                "attr2 decimal(10, 6)" +
                ") WITH (" +
                " partition_by_hash_columns = ARRAY['key1'], " +
                " partition_by_hash_buckets = 2" +
                ")");

        assertUpdate("INSERT INTO test_grouped_execution_t1 VALUES (0, 0, 0), (0, 1, 0), (1, 1, 1)", 3);
        assertUpdate("INSERT INTO test_grouped_execution_t2 VALUES (0, 0, 0), (1, 1, 1), (1, 2, 1)", 3);
        assertQuery(
                getSession(),
                "SELECT t1.* FROM test_grouped_execution_t1 t1 join test_grouped_execution_t2 t2 on t1.key1=t2.key1 WHERE t1.attr1=0",
                "VALUES (0, 0, 0), (0, 1, 0)",
                assertRemoteExchangesCount(1));

        assertUpdate("DROP TABLE test_grouped_execution_t1");
        assertUpdate("DROP TABLE test_grouped_execution_t2");
    }

    @Test
    public void testGroupedExecutionGroupBy()
    {
        assertUpdate("CREATE TABLE IF NOT EXISTS test_grouped_execution (" +
                "key1 INT WITH (primary_key=true), " +
                "key2 INT WITH (primary_key=true), " +
                "attr INT" +
                ") WITH (" +
                " partition_by_hash_columns = ARRAY['key1'], " +
                " partition_by_hash_buckets = 2" +
                ")");

        assertUpdate("INSERT INTO test_grouped_execution VALUES (0, 0, 0), (0, 1, 1), (1, 0, 1)", 3);
        assertQuery(
                getSession(),
                "SELECT key1, COUNT(1) FROM test_grouped_execution GROUP BY key1",
                "VALUES (0, 2), (1, 1)",
                assertRemoteExchangesCount(1));

        assertUpdate("DROP TABLE test_grouped_execution");
    }

    @Test
    public void testGroupedExecutionMultiLevelPartitioning()
    {
        assertUpdate("CREATE TABLE IF NOT EXISTS test_grouped_execution_mtlvl (" +
                "key1 BIGINT WITH (primary_key=true)," +
                "key2 BIGINT WITH (primary_key=true)," +
                "key3 BIGINT WITH (primary_key=true)," +
                "key4 BIGINT WITH (primary_key=true)," +
                "attr1 BIGINT" +
                ") WITH (" +
                " partition_by_hash_columns = ARRAY['key1', 'key2']," +
                " partition_by_hash_buckets = 2," +
                " partition_by_second_hash_columns = ARRAY['key3']," +
                " partition_by_second_hash_buckets = 2" +
                ")");

        assertUpdate("INSERT INTO test_grouped_execution_mtlvl VALUES (0, 0, 0, 0, 0), (0, 0, 0, 1, 1), (1, 1, 1, 0, 0), (1, 1, 1, 1, 1)", 4);
        assertQuery(
                getSession(),
                "SELECT key1, key2, key3, COUNT(1) FROM test_grouped_execution_mtlvl GROUP BY key1, key2, key3",
                "VALUES (0, 0, 0, 2), (1, 1, 1, 2)",
                assertRemoteExchangesCount(1));

        assertUpdate("DROP TABLE test_grouped_execution_mtlvl");
    }

    @Test
    public void testGroupedExecutionMultiLevelCombinedPartitioning()
    {
        assertUpdate("CREATE TABLE test_grouped_execution_hash_range (" +
                "key1 BIGINT WITH (primary_key=true)," +
                "key2 BIGINT WITH (primary_key=true)," +
                "key3 BIGINT WITH (primary_key=true)," +
                "key4 BIGINT WITH (primary_key=true)," +
                "attr1 BIGINT" +
                ") WITH (" +
                "  partition_by_hash_columns = ARRAY['key1']," +
                "  partition_by_hash_buckets = 2," +
                "  partition_by_second_hash_columns = ARRAY['key2']," +
                "  partition_by_second_hash_buckets = 3," +
                "  partition_by_range_columns = ARRAY['key3']," +
                "  range_partitions = '[{\"lower\": null, \"upper\": \"4\"}, {\"lower\": \"4\", \"upper\": \"9\"}, {\"lower\": \"9\", \"upper\": null}]'" +
                ")");

        assertUpdate("INSERT INTO test_grouped_execution_hash_range VALUES (0, 0, 0, 0, 0), (0, 0, 9, 0, 9), (0, 0, 9, 1, 0), (1, 1, 0, 0, 1), (1, 1, 9, 0, 2)", 5);
        assertQuery(
                getSession(),
                "SELECT key1, key2, key3, COUNT(1) FROM test_grouped_execution_hash_range GROUP BY key1, key2, key3",
                "VALUES (0, 0, 0, 1), (0, 0, 9, 2), (1, 1, 0, 1), (1, 1, 9, 1)",
                assertRemoteExchangesCount(1));

        assertUpdate("DROP TABLE test_grouped_execution_hash_range");
    }

    private Consumer<Plan> assertRemoteExchangesCount(int expectedRemoteExchangesCount)
    {
        return plan -> {
            int actualRemoteExchangesCount = searchFrom(plan.getRoot())
                    .where(node -> node instanceof ExchangeNode && ((ExchangeNode) node).getScope() == ExchangeNode.Scope.REMOTE)
                    .findAll()
                    .size();
            if (actualRemoteExchangesCount != expectedRemoteExchangesCount) {
                Session session = getSession();
                Metadata metadata = ((DistributedQueryRunner) getQueryRunner()).getCoordinator().getMetadata();
                String formattedPlan = textLogicalPlan(plan.getRoot(), plan.getTypes(), metadata, StatsAndCosts.empty(), session, 0, false);
                throw new AssertionError(format(
                        "Expected %s remote exchanges but found %s. Actual plan is:\n%s]",
                        expectedRemoteExchangesCount,
                        actualRemoteExchangesCount,
                        formattedPlan));
            }
        };
    }
}
