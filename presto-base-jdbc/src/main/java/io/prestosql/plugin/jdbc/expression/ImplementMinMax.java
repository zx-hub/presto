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
package io.prestosql.plugin.jdbc.expression;

import io.prestosql.matching.Capture;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.plugin.jdbc.JdbcColumnHandle;
import io.prestosql.plugin.jdbc.JdbcExpression;
import io.prestosql.spi.connector.AggregateFunction;
import io.prestosql.spi.expression.Variable;

import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static io.prestosql.matching.Capture.newCapture;
import static io.prestosql.plugin.jdbc.expression.AggregateFunctionPatterns.basicAggregation;
import static io.prestosql.plugin.jdbc.expression.AggregateFunctionPatterns.functionName;
import static io.prestosql.plugin.jdbc.expression.AggregateFunctionPatterns.singleInput;
import static io.prestosql.plugin.jdbc.expression.AggregateFunctionPatterns.variable;
import static java.lang.String.format;

/**
 * Implements {@code min(x)}, {@code max(x)}.
 */
public class ImplementMinMax
        implements AggregateFunctionRule
{
    private static final Capture<Variable> INPUT = newCapture();

    @Override
    public Pattern<AggregateFunction> getPattern()
    {
        return basicAggregation()
                .with(functionName().matching(Set.of("min", "max")::contains))
                .with(singleInput().matching(variable().capturedAs(INPUT)));
    }

    @Override
    public Optional<JdbcExpression> rewrite(AggregateFunction aggregateFunction, Captures captures, RewriteContext context)
    {
        Variable input = captures.get(INPUT);
        JdbcColumnHandle columnHandle = (JdbcColumnHandle) context.getAssignments().get(input.getName());
        verifyNotNull(columnHandle, "Unbound variable: %s", input);
        verify(columnHandle.getColumnType().equals(aggregateFunction.getOutputType()));

        return Optional.of(new JdbcExpression(
                format("%s(%s)", aggregateFunction.getFunctionName(), columnHandle.toSqlExpression(context.getIdentifierQuote())),
                columnHandle.getJdbcTypeHandle()));
    }
}
