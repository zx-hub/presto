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
package io.prestosql.execution;

import io.prestosql.sql.parser.ParsingOptions;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.tree.NodeLocation;
import io.prestosql.sql.tree.Parameter;
import io.prestosql.sql.tree.Statement;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestParameterExtractor
{
    private final SqlParser sqlParser = new SqlParser();

    @Test
    public void testNoParameter()
    {
        Statement statement = sqlParser.createStatement("SELECT c1, c2 FROM test_table WHERE c1 = 1 AND c2 > 2", new ParsingOptions());
        assertThat(ParameterExtractor.getParameters(statement)).isEmpty();
        assertThat(ParameterExtractor.getParameterCount(statement)).isEqualTo(0);
    }

    @Test
    public void testParameterCount()
    {
        Statement statement = sqlParser.createStatement("SELECT c1, c2 FROM test_table WHERE c1 = ? AND c2 > ?", new ParsingOptions());
        assertThat(ParameterExtractor.getParameters(statement))
                .containsExactly(
                        new Parameter(new NodeLocation(1, 41), 0),
                        new Parameter(new NodeLocation(1, 52), 1));
        assertThat(ParameterExtractor.getParameterCount(statement)).isEqualTo(2);
    }
}
