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

package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.plugin.tpch.TpchConnectorFactory;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.sql.analyzer.FeaturesConfig.JoinDistributionType;
import io.prestosql.sql.analyzer.FeaturesConfig.JoinReorderingStrategy;
import io.prestosql.sql.planner.assertions.BasePlanTest;
import io.prestosql.sql.planner.assertions.PlanMatchPattern;
import io.prestosql.sql.planner.assertions.RowNumberSymbolMatcher;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.JoinNode.DistributionType;
import io.prestosql.sql.planner.plan.MarkDistinctNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.testing.LocalQueryRunner;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.SystemSessionProperties.IGNORE_DOWNSTREAM_PREFERENCES;
import static io.prestosql.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.prestosql.SystemSessionProperties.JOIN_REORDERING_STRATEGY;
import static io.prestosql.SystemSessionProperties.SPILL_ENABLED;
import static io.prestosql.SystemSessionProperties.TASK_CONCURRENCY;
import static io.prestosql.sql.analyzer.FeaturesConfig.JoinDistributionType.PARTITIONED;
import static io.prestosql.sql.analyzer.FeaturesConfig.JoinReorderingStrategy.ELIMINATE_CROSS_JOINS;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.aggregation;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.any;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.anyNot;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.equiJoinClause;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.exchange;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.filter;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.functionCall;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.join;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.limit;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.node;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.output;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.rowNumber;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.sort;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.topN;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.planner.plan.AggregationNode.Step.PARTIAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.REMOTE;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.GATHER;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPLICATE;
import static io.prestosql.sql.planner.plan.JoinNode.DistributionType.REPLICATED;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.planner.plan.TopNNode.Step.FINAL;
import static io.prestosql.sql.tree.SortItem.NullOrdering.LAST;
import static io.prestosql.sql.tree.SortItem.Ordering.ASCENDING;
import static io.prestosql.testing.TestingSession.testSessionBuilder;

public class TestAddExchangesPlans
        extends BasePlanTest
{
    @Override
    protected LocalQueryRunner createLocalQueryRunner()
    {
        Session session = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("tiny")
                .build();
        FeaturesConfig featuresConfig = new FeaturesConfig()
                .setSpillerSpillPaths("/tmp/test_spill_path");
        LocalQueryRunner queryRunner = LocalQueryRunner.builder(session)
                .withFeaturesConfig(featuresConfig)
                .build();
        queryRunner.createCatalog("tpch", new TpchConnectorFactory(1), ImmutableMap.of());
        return queryRunner;
    }

    @Test
    public void testRepartitionForUnionWithAnyTableScans()
    {
        assertDistributedPlan("SELECT nationkey FROM nation UNION select regionkey from region",
                anyTree(
                        aggregation(ImmutableMap.of(),
                                anyTree(
                                        anyTree(
                                                exchange(REMOTE, REPARTITION,
                                                        anyTree(
                                                                tableScan("nation")))),
                                        anyTree(
                                                exchange(REMOTE, REPARTITION,
                                                        anyTree(
                                                                tableScan("region"))))))));
        assertDistributedPlan("SELECT nationkey FROM nation UNION select 1",
                anyTree(
                        aggregation(ImmutableMap.of(),
                                anyTree(
                                        anyTree(
                                                exchange(REMOTE, REPARTITION,
                                                        anyTree(
                                                                tableScan("nation")))),
                                        anyTree(
                                                exchange(REMOTE, REPARTITION,
                                                        anyTree(
                                                                values())))))));
    }

    @Test
    public void testRepartitionForUnionAllBeforeHashJoin()
    {
        Session session = Session.builder(getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, DistributionType.PARTITIONED.name())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, ELIMINATE_CROSS_JOINS.name())
                .build();

        assertDistributedPlan("SELECT * FROM (SELECT nationkey FROM nation UNION ALL select nationkey from nation) n join region r on n.nationkey = r.regionkey",
                session,
                anyTree(
                        join(INNER, ImmutableList.of(equiJoinClause("nationkey", "regionkey")),
                                anyTree(
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        tableScan("nation", ImmutableMap.of("nationkey", "nationkey")))),
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        tableScan("nation")))),
                                anyTree(
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        tableScan("region", ImmutableMap.of("regionkey", "regionkey"))))))));

        assertDistributedPlan("SELECT * FROM (SELECT nationkey FROM nation UNION ALL select 1) n join region r on n.nationkey = r.regionkey",
                session,
                anyTree(
                        join(INNER, ImmutableList.of(equiJoinClause("nationkey", "regionkey")),
                                anyTree(
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        tableScan("nation", ImmutableMap.of("nationkey", "nationkey")))),
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        values()))),
                                anyTree(
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        tableScan("region", ImmutableMap.of("regionkey", "regionkey"))))))));
    }

    @Test
    public void testNonSpillableBroadcastJoinAboveTableScan()
    {
        assertDistributedPlan(
                "SELECT * FROM nation n join region r on n.nationkey = r.regionkey",
                noJoinReordering(),
                anyTree(
                        join(INNER, ImmutableList.of(equiJoinClause("nationkey", "regionkey")), Optional.empty(), Optional.of(REPLICATED), Optional.of(false),
                                anyNot(ExchangeNode.class,
                                        node(
                                                FilterNode.class,
                                                tableScan("nation", ImmutableMap.of("nationkey", "nationkey")))),
                                anyTree(
                                        exchange(REMOTE, REPLICATE,
                                                anyTree(
                                                        tableScan("region", ImmutableMap.of("regionkey", "regionkey"))))))));

        assertDistributedPlan(
                "SELECT * FROM nation n join region r on n.nationkey = r.regionkey",
                spillEnabledWithJoinDistributionType(PARTITIONED),
                anyTree(
                        join(INNER, ImmutableList.of(equiJoinClause("nationkey", "regionkey")), Optional.empty(), Optional.of(DistributionType.PARTITIONED), Optional.empty(),
                                exchange(REMOTE, REPARTITION,
                                        anyTree(
                                                tableScan("nation", ImmutableMap.of("nationkey", "nationkey")))),
                                exchange(LOCAL, REPARTITION,
                                        exchange(REMOTE, REPARTITION,
                                                anyTree(
                                                        tableScan("region", ImmutableMap.of("regionkey", "regionkey"))))))));
    }

    @Test
    public void testForcePartitioningMarkDistinctInput()
    {
        String query = "SELECT count(orderkey), count(distinct orderkey), custkey , count(1) FROM ( SELECT * FROM (VALUES (1, 2)) as t(custkey, orderkey) UNION ALL SELECT 3, 4) GROUP BY 3";
        assertDistributedPlan(
                query,
                Session.builder(getQueryRunner().getDefaultSession())
                        .setSystemProperty(IGNORE_DOWNSTREAM_PREFERENCES, "true")
                        .build(),
                anyTree(
                        node(MarkDistinctNode.class,
                                anyTree(
                                        exchange(REMOTE, REPARTITION, ImmutableList.of(), ImmutableSet.of("partition1", "partition2"),
                                                anyTree(
                                                        values(ImmutableList.of("partition1", "partition2")))),
                                        exchange(REMOTE, REPARTITION, ImmutableList.of(), ImmutableSet.of("partition3", "partition3"),
                                                project(
                                                        project(ImmutableMap.of("partition3", expression("3"), "partition4", expression("4")),
                                                                anyTree(
                                                                        node(ValuesNode.class)))))))));

        assertDistributedPlan(
                query,
                Session.builder(getQueryRunner().getDefaultSession())
                        .setSystemProperty(IGNORE_DOWNSTREAM_PREFERENCES, "false")
                        .build(),
                anyTree(
                        node(MarkDistinctNode.class,
                                anyTree(
                                        exchange(REMOTE, REPARTITION, ImmutableList.of(), ImmutableSet.of("partition1"),
                                                anyTree(
                                                        values(ImmutableList.of("partition1", "partition2")))),
                                        exchange(REMOTE, REPARTITION, ImmutableList.of(), ImmutableSet.of("partition3"),
                                                project(
                                                        project(ImmutableMap.of("partition3", expression("3"), "partition4", expression("4")),
                                                                anyTree(
                                                                        node(ValuesNode.class)))))))));
    }

    @Test
    public void testImplementOffsetWithOrderedSource()
    {
        // no repartitioning exchange is added below row number, so the ordering established by topN is preserved.
        // also, no repartitioning exchange is added above row number, so the order is respected at output.
        assertPlan(
                "SELECT name FROM nation ORDER BY regionkey, name OFFSET 5 LIMIT 2",
                output(
                        project(
                                ImmutableMap.of("name", PlanMatchPattern.expression("name")),
                                filter(
                                        "row_num > BIGINT '5'",
                                        rowNumber(
                                                pattern -> pattern
                                                        .partitionBy(ImmutableList.of()),
                                                project(
                                                        ImmutableMap.of("name", PlanMatchPattern.expression("name")),
                                                        topN(
                                                                7,
                                                                ImmutableList.of(sort("regionkey", ASCENDING, LAST), sort("name", ASCENDING, LAST)),
                                                                FINAL,
                                                                anyTree(
                                                                        tableScan("nation", ImmutableMap.of("NAME", "name", "REGIONKEY", "regionkey"))))))
                                                .withAlias("row_num", new RowNumberSymbolMatcher())))));
    }

    @Test
    public void testImplementOffsetWithUnorderedSource()
    {
        // no ordering of output is expected; repartitioning exchange is present in the plan
        assertPlan(
                "SELECT name FROM nation OFFSET 5 LIMIT 2",
                any(
                        project(
                                ImmutableMap.of("name", PlanMatchPattern.expression("name")),
                                filter(
                                        "row_num > BIGINT '5'",
                                        exchange(
                                                LOCAL,
                                                REPARTITION,
                                                rowNumber(
                                                        pattern -> pattern
                                                                .partitionBy(ImmutableList.of()),
                                                        limit(
                                                                7,
                                                                anyTree(
                                                                        tableScan("nation", ImmutableMap.of("NAME", "name")))))
                                                        .withAlias("row_num", new RowNumberSymbolMatcher()))))));
    }

    @Test
    public void testExchangesAroundTrivialProjection()
    {
        // * source of Projection is single stream (topN)
        // * parent of Projection requires single stream distribution (rowNumber)
        // ==> Projection is planned with single stream distribution.
        assertPlan(
                "SELECT name, row_number() OVER () FROM (SELECT * FROM nation ORDER BY nationkey LIMIT 5)",
                any(
                        rowNumber(
                                pattern -> pattern
                                        .partitionBy(ImmutableList.of()),
                                project(
                                        ImmutableMap.of("name", expression("name")),
                                        topN(
                                                5,
                                                ImmutableList.of(sort("nationkey", ASCENDING, LAST)),
                                                FINAL,
                                                anyTree(
                                                        tableScan("nation", ImmutableMap.of("NAME", "name", "NATIONKEY", "nationkey"))))))));

        // * source of Projection is distributed (filter)
        // * parent of Projection requires single distribution (rowNumber)
        // ==> Projection is planned with multiple distribution and gathering exchange is added on top of Projection.
        assertPlan(
                "SELECT b, row_number() OVER () FROM (VALUES (1, 2)) t(a, b) WHERE a < 10",
                any(
                        rowNumber(
                                pattern -> pattern
                                        .partitionBy(ImmutableList.of()),
                                exchange(
                                        LOCAL,
                                        GATHER,
                                        project(
                                                ImmutableMap.of("b", expression("b")),
                                                filter(
                                                        "a < 10",
                                                        exchange(
                                                                LOCAL,
                                                                REPARTITION,
                                                                values("a", "b"))))))));

        // * source of Projection is single stream (topN)
        // * parent of Projection requires hashed multiple distribution (rowNumber).
        // ==> Projection is planned with single distribution. Hash partitioning exchange is added on top of Projection.
        assertPlan(
                "SELECT row_number() OVER (PARTITION BY regionkey) FROM (SELECT * FROM nation ORDER BY nationkey LIMIT 5)",
                anyTree(
                        rowNumber(
                                pattern -> pattern
                                        .partitionBy(ImmutableList.of("regionkey"))
                                        .hashSymbol(Optional.of("hash")),
                                exchange(
                                        LOCAL,
                                        REPARTITION,
                                        ImmutableList.of(),
                                        ImmutableSet.of("regionkey"),
                                        project(
                                                ImmutableMap.of("regionkey", expression("regionkey"), "hash", expression("hash")),
                                                topN(
                                                        5,
                                                        ImmutableList.of(sort("nationkey", ASCENDING, LAST)),
                                                        FINAL,
                                                        any(
                                                                project(
                                                                        ImmutableMap.of(
                                                                                "regionkey", expression("regionkey"),
                                                                                "nationkey", expression("nationkey"),
                                                                                "hash", expression("combine_hash(bigint '0', COALESCE(\"$operator$hash_code\"(regionkey), 0))")),
                                                                        any(
                                                                                tableScan("nation", ImmutableMap.of("REGIONKEY", "regionkey", "NATIONKEY", "nationkey")))))))))));

        // * source of Projection is distributed (filter)
        // * parent of Projection requires hashed multiple distribution (rowNumber).
        // ==> Projection is planned with multiple distribution (no exchange added below). Hash partitioning exchange is added on top of Projection.
        assertPlan(
                "SELECT row_number() OVER (PARTITION BY b) FROM (VALUES (1, 2)) t(a,b) WHERE a < 10",
                anyTree(
                        rowNumber(
                                pattern -> pattern
                                        .partitionBy(ImmutableList.of("b"))
                                        .hashSymbol(Optional.of("hash")),
                                exchange(
                                        LOCAL,
                                        REPARTITION,
                                        ImmutableList.of(),
                                        ImmutableSet.of("b"),
                                        project(
                                                ImmutableMap.of("b", expression("b"), "hash", expression("hash")),
                                                filter(
                                                        "a < 10",
                                                        exchange(
                                                                LOCAL,
                                                                REPARTITION,
                                                                project(
                                                                        ImmutableMap.of(
                                                                                "a", expression("a"),
                                                                                "b", expression("b"),
                                                                                "hash", expression("combine_hash(bigint '0', COALESCE(\"$operator$hash_code\"(b), 0))")),
                                                                        values("a", "b")))))))));

        // * source of Projection is single stream (topN)
        // * parent of Projection requires random multiple distribution (partial aggregation)
        // ==> Projection is planned with multiple distribution (round robin exchange is added below).
        assertPlan(
                "SELECT count(name) FROM (SELECT * FROM nation ORDER BY nationkey LIMIT 5)",
                anyTree(
                        aggregation(
                                ImmutableMap.of("count", functionCall("count", ImmutableList.of("name"))),
                                PARTIAL,
                                project(
                                        ImmutableMap.of("name", expression("name")),
                                        exchange(
                                                LOCAL,
                                                REPARTITION,
                                                topN(
                                                        5,
                                                        ImmutableList.of(sort("nationkey", ASCENDING, LAST)),
                                                        FINAL,
                                                        anyTree(
                                                                tableScan("nation", ImmutableMap.of("NAME", "name", "NATIONKEY", "nationkey")))))))));

        // * source of Projection is distributed (filter)
        // * parent of Projection requires random multiple distribution (aggregation)
        // ==> Projection is planned with multiple distribution (no exchange added)
        assertPlan(
                "SELECT count(b) FROM (VALUES (1, 2)) t(a,b) WHERE a < 10",
                anyTree(
                        aggregation(
                                ImmutableMap.of("count", functionCall("count", ImmutableList.of("b"))),
                                PARTIAL,
                                project(
                                        ImmutableMap.of("b", expression("b")),
                                        filter(
                                                "a < 10",
                                                exchange(
                                                        LOCAL,
                                                        REPARTITION,
                                                        values("a", "b")))))));

        assertPlan(
                "SELECT 10, a FROM (VALUES 1) t(a)",
                anyTree(
                        project(
                                values("a"))));

        assertPlan(
                "SELECT 1 UNION ALL SELECT 1",
                anyTree(
                        exchange(
                                LOCAL,
                                REPARTITION,
                                project(
                                        values()),
                                project(
                                        values()))));
    }

    private Session spillEnabledWithJoinDistributionType(JoinDistributionType joinDistributionType)
    {
        return Session.builder(getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, joinDistributionType.toString())
                .setSystemProperty(SPILL_ENABLED, "true")
                .setSystemProperty(TASK_CONCURRENCY, "16")
                .build();
    }

    private Session noJoinReordering()
    {
        return Session.builder(getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, JoinReorderingStrategy.NONE.name())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.BROADCAST.name())
                .setSystemProperty(SPILL_ENABLED, "true")
                .setSystemProperty(TASK_CONCURRENCY, "16")
                .build();
    }
}
