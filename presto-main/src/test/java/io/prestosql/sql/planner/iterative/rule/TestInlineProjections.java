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
import io.prestosql.sql.planner.assertions.ExpressionMatcher;
import io.prestosql.sql.planner.assertions.PlanMatchPattern;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.plan.Assignments;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.expression;

public class TestInlineProjections
        extends BaseRuleTest
{
    private static final RowType MSG_TYPE = RowType.from(ImmutableList.of(new RowType.Field(Optional.of("x"), VARCHAR), new RowType.Field(Optional.of("y"), VARCHAR)));

    @Test
    public void test()
    {
        tester().assertThat(new InlineProjections())
                .on(p ->
                        p.project(
                                Assignments.builder()
                                        .put(p.symbol("identity"), expression("symbol")) // identity
                                        .put(p.symbol("multi_complex_1"), expression("complex + 1")) // complex expression referenced multiple times
                                        .put(p.symbol("multi_complex_2"), expression("complex + 2")) // complex expression referenced multiple times
                                        .put(p.symbol("multi_literal_1"), expression("literal + 1")) // literal referenced multiple times
                                        .put(p.symbol("multi_literal_2"), expression("literal + 2")) // literal referenced multiple times
                                        .put(p.symbol("single_complex"), expression("complex_2 + 2")) // complex expression reference only once
                                        .put(p.symbol("try"), expression("try(complex / literal)"))
                                        .put(p.symbol("msg_xx"), expression("z + 1"))
                                        .build(),
                                p.project(Assignments.builder()
                                                .put(p.symbol("symbol"), expression("x"))
                                                .put(p.symbol("complex"), expression("x * 2"))
                                                .put(p.symbol("literal"), expression("1"))
                                                .put(p.symbol("complex_2"), expression("x - 1"))
                                                .put(p.symbol("z"), expression("msg.x"))
                                                .build(),
                                        p.values(p.symbol("x"), p.symbol("msg", MSG_TYPE)))))
                .matches(
                        project(
                                ImmutableMap.<String, ExpressionMatcher>builder()
                                        .put("out1", PlanMatchPattern.expression("x"))
                                        .put("out2", PlanMatchPattern.expression("y + 1"))
                                        .put("out3", PlanMatchPattern.expression("y + 2"))
                                        .put("out4", PlanMatchPattern.expression("1 + 1"))
                                        .put("out5", PlanMatchPattern.expression("1 + 2"))
                                        .put("out6", PlanMatchPattern.expression("x - 1 + 2"))
                                        .put("out7", PlanMatchPattern.expression("try(y / 1)"))
                                        .put("out8", PlanMatchPattern.expression("z + 1"))
                                        .build(),
                                project(
                                        ImmutableMap.of(
                                                "x", PlanMatchPattern.expression("x"),
                                                "y", PlanMatchPattern.expression("x * 2"),
                                                "z", PlanMatchPattern.expression("msg.x")),
                                        values(ImmutableMap.of("x", 0, "msg", 1)))));
    }

    @Test
    public void testEliminatesIdentityProjection()
    {
        tester().assertThat(new InlineProjections())
                .on(p ->
                        p.project(
                                Assignments.builder()
                                        .put(p.symbol("single_complex"), expression("complex + 2")) // complex expression referenced only once
                                        .build(),
                                p.project(Assignments.builder()
                                                .put(p.symbol("complex"), expression("x - 1"))
                                                .build(),
                                        p.values(p.symbol("x")))))
                .matches(
                        project(
                                ImmutableMap.<String, ExpressionMatcher>builder()
                                        .put("out1", PlanMatchPattern.expression("x - 1 + 2"))
                                        .build(),
                                values(ImmutableMap.of("x", 0))));
    }

    @Test
    public void testIdentityProjections()
    {
        tester().assertThat(new InlineProjections())
                .on(p ->
                        p.project(
                                Assignments.of(p.symbol("output"), expression("value")),
                                p.project(
                                        Assignments.identity(p.symbol("value")),
                                        p.values(p.symbol("value")))))
                .doesNotFire();
    }

    @Test
    public void testSubqueryProjections()
    {
        tester().assertThat(new InlineProjections())
                .on(p ->
                        p.project(
                                Assignments.identity(p.symbol("fromOuterScope"), p.symbol("value")),
                                p.project(
                                        Assignments.identity(p.symbol("value")),
                                        p.values(p.symbol("value")))))
                .doesNotFire();
    }
}
