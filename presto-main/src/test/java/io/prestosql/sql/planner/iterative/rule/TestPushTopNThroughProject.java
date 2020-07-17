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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.spi.type.RowType;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.assertions.ExpressionMatcher;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.tree.ArithmeticBinaryExpression;
import io.prestosql.sql.tree.BooleanLiteral;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.testing.TestingMetadata;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.sort;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.topN;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.tree.ArithmeticBinaryExpression.Operator.ADD;
import static io.prestosql.sql.tree.SortItem.NullOrdering.FIRST;
import static io.prestosql.sql.tree.SortItem.Ordering.ASCENDING;

public class TestPushTopNThroughProject
        extends BaseRuleTest
{
    private static final RowType rowType = RowType.from(ImmutableList.of(
            new RowType.Field(Optional.of("x"), BIGINT),
            new RowType.Field(Optional.of("y"), BIGINT)));

    @Test
    public void testPushdownTopNNonIdentityProjection()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol projectedA = p.symbol("projectedA");
                    Symbol a = p.symbol("a");
                    Symbol projectedB = p.symbol("projectedB");
                    Symbol b = p.symbol("b");
                    return p.topN(
                            1,
                            ImmutableList.of(projectedA),
                            p.project(
                                    Assignments.of(projectedA, new SymbolReference("a"), projectedB, new SymbolReference("b")),
                                    p.values(a, b)));
                })
                .matches(
                        project(
                                ImmutableMap.of("projectedA", new ExpressionMatcher("a"), "projectedB", new ExpressionMatcher("b")),
                                topN(1, ImmutableList.of(sort("a", ASCENDING, FIRST)), values("a", "b"))));
    }

    @Test
    public void testPushdownTopNNonIdentityProjectionWithExpression()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol projectedA = p.symbol("projectedA");
                    Symbol a = p.symbol("a");
                    Symbol projectedC = p.symbol("projectedC");
                    Symbol b = p.symbol("b");
                    return p.topN(
                            1,
                            ImmutableList.of(projectedA),
                            p.project(
                                    Assignments.of(
                                            projectedA, new SymbolReference("a"),
                                            projectedC, new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), new SymbolReference("b"))),
                                    p.values(a, b)));
                })
                .matches(
                        project(
                                ImmutableMap.of("projectedA", new ExpressionMatcher("a"), "projectedC", new ExpressionMatcher("a + b")),
                                topN(1, ImmutableList.of(sort("a", ASCENDING, FIRST)), values("a", "b"))));
    }

    @Test
    public void testDoNotPushdownTopNThroughIdentityProjection()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    return p.topN(1,
                            ImmutableList.of(a),
                            p.project(
                                    Assignments.of(a, a.toSymbolReference()),
                                    p.values(a)));
                }).doesNotFire();
    }

    @Test
    public void testDoNotPushdownTopNThroughProjectionOverFilterOverTableScan()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol projectedA = p.symbol("projectedA");
                    Symbol a = p.symbol("a");
                    return p.topN(
                            1,
                            ImmutableList.of(projectedA),
                            p.project(
                                    Assignments.of(projectedA, new SymbolReference("a")),
                                    p.filter(
                                            BooleanLiteral.TRUE_LITERAL,
                                            p.tableScan(ImmutableList.of(), ImmutableMap.of()))));
                }).doesNotFire();
    }

    @Test
    public void testDoNotPushdownTopNThroughProjectionOverTableScan()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol projectedA = p.symbol("projectedA");
                    Symbol a = p.symbol("a");
                    return p.topN(
                            1,
                            ImmutableList.of(projectedA),
                            p.project(
                                    Assignments.of(projectedA, new SymbolReference("a")),
                                    p.tableScan(
                                            ImmutableList.of(a),
                                            ImmutableMap.of(a, new TestingMetadata.TestingColumnHandle("a")))));
                }).doesNotFire();
    }

    @Test
    public void testDoesntPushDownTopNThroughExclusiveDereferences()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol a = p.symbol("a", rowType);
                    return p.topN(
                            1,
                            ImmutableList.of(p.symbol("c")),
                            p.project(
                                    Assignments.builder()
                                            .put(p.symbol("b"), new DereferenceExpression(a.toSymbolReference(), new Identifier("x")))
                                            .put(p.symbol("c"), new DereferenceExpression(a.toSymbolReference(), new Identifier("y")))
                                            .build(),
                                    p.values(a)));
                }).doesNotFire();
    }

    @Test
    public void testPushTopNThroughOverlappingDereferences()
    {
        tester().assertThat(new PushTopNThroughProject())
                .on(p -> {
                    Symbol a = p.symbol("a", rowType);
                    Symbol d = p.symbol("d");
                    return p.topN(
                            1,
                            ImmutableList.of(d),
                            p.project(
                                    Assignments.builder()
                                            .put(p.symbol("b"), new DereferenceExpression(a.toSymbolReference(), new Identifier("x")))
                                            .put(p.symbol("c", rowType), a.toSymbolReference())
                                            .put(d, d.toSymbolReference())
                                            .build(),
                                    p.values(a, d)));
                })
                .matches(
                        project(
                                ImmutableMap.of("b", expression("a.x"), "c", expression("a"), "d", expression("d")),
                                topN(
                                        1,
                                        ImmutableList.of(sort("d", ASCENDING, FIRST)),
                                        values("a", "d"))));
    }
}
