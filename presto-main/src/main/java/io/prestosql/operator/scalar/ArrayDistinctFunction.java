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
package io.prestosql.operator.scalar;

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.aggregation.TypedSet;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.Convention;
import io.prestosql.spi.function.Description;
import io.prestosql.spi.function.OperatorDependency;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.type.Type;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.lang.invoke.MethodHandle;

import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.BLOCK_POSITION;
import static io.prestosql.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.prestosql.spi.function.OperatorType.IS_DISTINCT_FROM;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.util.Failures.internalError;

@ScalarFunction("array_distinct")
@Description("Remove duplicate values from the given array")
public final class ArrayDistinctFunction
{
    private final PageBuilder pageBuilder;

    @TypeParameter("E")
    public ArrayDistinctFunction(@TypeParameter("E") Type elementType)
    {
        pageBuilder = new PageBuilder(ImmutableList.of(elementType));
    }

    @TypeParameter("E")
    @SqlType("array(E)")
    public Block distinct(
            @TypeParameter("E") Type type,
            @OperatorDependency(
                    operator = IS_DISTINCT_FROM,
                    argumentTypes = {"E", "E"},
                    convention = @Convention(arguments = {BLOCK_POSITION, BLOCK_POSITION}, result = FAIL_ON_NULL)) MethodHandle elementIsDistinctFrom,
            @SqlType("array(E)") Block array)
    {
        if (array.getPositionCount() < 2) {
            return array;
        }

        if (array.getPositionCount() == 2) {
            boolean distinct;
            try {
                distinct = (boolean) elementIsDistinctFrom.invoke(array, 0, array, 1);
            }
            catch (Throwable t) {
                throw internalError(t);
            }
            if (distinct) {
                return array;
            }
            return array.getSingleValueBlock(0);
        }

        TypedSet typedSet = new TypedSet(type, elementIsDistinctFrom, array.getPositionCount(), "array_distinct");
        int distinctCount = 0;

        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        BlockBuilder distinctElementBlockBuilder = pageBuilder.getBlockBuilder(0);
        for (int i = 0; i < array.getPositionCount(); i++) {
            if (!typedSet.contains(array, i)) {
                typedSet.add(array, i);
                distinctCount++;
                type.appendTo(array, i, distinctElementBlockBuilder);
            }
        }

        pageBuilder.declarePositions(distinctCount);

        return distinctElementBlockBuilder.getRegion(distinctElementBlockBuilder.getPositionCount() - distinctCount, distinctCount);
    }

    @SqlType("array(bigint)")
    public Block bigintDistinct(@SqlType("array(bigint)") Block array)
    {
        if (array.getPositionCount() == 0) {
            return array;
        }

        boolean containsNull = false;
        LongSet set = new LongOpenHashSet(array.getPositionCount());
        int distinctCount = 0;

        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        BlockBuilder distinctElementBlockBuilder = pageBuilder.getBlockBuilder(0);
        for (int i = 0; i < array.getPositionCount(); i++) {
            if (array.isNull(i)) {
                if (!containsNull) {
                    containsNull = true;
                    distinctElementBlockBuilder.appendNull();
                    distinctCount++;
                }
                continue;
            }
            long value = BIGINT.getLong(array, i);
            if (!set.contains(value)) {
                set.add(value);
                distinctCount++;
                BIGINT.appendTo(array, i, distinctElementBlockBuilder);
            }
        }

        pageBuilder.declarePositions(distinctCount);

        return distinctElementBlockBuilder.getRegion(distinctElementBlockBuilder.getPositionCount() - distinctCount, distinctCount);
    }
}
