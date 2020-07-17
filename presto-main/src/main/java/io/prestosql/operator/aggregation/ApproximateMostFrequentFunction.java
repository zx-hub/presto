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
package io.prestosql.operator.aggregation;

import io.airlift.slice.Slice;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.AccumulatorState;
import io.prestosql.spi.function.AccumulatorStateMetadata;
import io.prestosql.spi.function.AggregationFunction;
import io.prestosql.spi.function.AggregationState;
import io.prestosql.spi.function.CombineFunction;
import io.prestosql.spi.function.InputFunction;
import io.prestosql.spi.function.OutputFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.VarcharType;

import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.type.StandardTypes.BIGINT;
import static io.prestosql.util.Failures.checkCondition;
import static java.lang.Math.toIntExact;

/**
 *  <p>
 *  Aggregation function that approximates the frequency of the top-K elements.
 *  This function keeps counts for a "frequent" subset of elements and assumes all other elements
 *  once fewer than the least-frequent "frequent" element.
 *  </p>
 *
 * <p>
 * The algorithm is based loosely on:
 * <a href="https://dl.acm.org/doi/10.1007/978-3-540-30570-5_27">Efficient Computation of Frequent and Top-*k* Elements in Data Streams</a>
 * by Ahmed Metwally, Divyakant Agrawal, and Amr El Abbadi
 * </p>
 */
@AggregationFunction("approx_most_frequent")
public final class ApproximateMostFrequentFunction
{
    private ApproximateMostFrequentFunction() {}

    public interface State<K>
            extends AccumulatorState
    {
        ApproximateMostFrequentHistogram<K> get();

        void set(ApproximateMostFrequentHistogram<K> value);
    }

    @AccumulatorStateMetadata(stateSerializerClass = LongApproximateMostFrequentStateSerializer.class, stateFactoryClass = LongApproximateMostFrequentStateFactory.class)
    public interface LongState
            extends State<Long>
    {}

    @AccumulatorStateMetadata(stateSerializerClass = StringApproximateMostFrequentStateSerializer.class, stateFactoryClass = StringApproximateMostFrequentStateFactory.class)
    public interface StringState
            extends State<Slice>
    {}

    @InputFunction
    @TypeParameter("T")
    public static void input(@AggregationState LongState state, @SqlType(BIGINT) long buckets, @SqlType("T") long value, @SqlType(BIGINT) long capacity)
    {
        ApproximateMostFrequentHistogram<Long> histogram = state.get();
        if (histogram == null) {
            checkCondition(buckets >= 2, INVALID_FUNCTION_ARGUMENT, "approx_most_frequent bucket count must be greater than one");
            histogram = new ApproximateMostFrequentHistogram<Long>(
                    toIntExact(buckets),
                    toIntExact(capacity),
                    LongApproximateMostFrequentStateSerializer::serializeBucket,
                    LongApproximateMostFrequentStateSerializer::deserializeBucket);
            state.set(histogram);
        }

        histogram.add(value);
    }

    @InputFunction
    @TypeParameter("T")
    public static void input(@AggregationState StringState state, @SqlType(BIGINT) long buckets, @SqlType("T") Slice value, @SqlType(BIGINT) long capacity)
    {
        ApproximateMostFrequentHistogram<Slice> histogram = state.get();
        if (histogram == null) {
            checkCondition(buckets >= 2, INVALID_FUNCTION_ARGUMENT, "approx_most_frequent bucket count must be greater than one");
            histogram = new ApproximateMostFrequentHistogram<Slice>(
                    toIntExact(buckets),
                    toIntExact(capacity),
                    StringApproximateMostFrequentStateSerializer::serializeBucket,
                    StringApproximateMostFrequentStateSerializer::deserializeBucket);
            state.set(histogram);
        }

        histogram.add(value);
    }

    @CombineFunction
    public static void combine(@AggregationState LongState state, @AggregationState LongState otherState)
    {
        ApproximateMostFrequentHistogram<Long> otherHistogram = otherState.get();

        ApproximateMostFrequentHistogram<Long> histogram = state.get();
        if (histogram == null) {
            state.set(otherHistogram);
        }
        else {
            histogram.merge(otherHistogram);
        }
    }

    @CombineFunction
    public static void combine(@AggregationState StringState state, @AggregationState StringState otherState)
    {
        ApproximateMostFrequentHistogram<Slice> otherHistogram = otherState.get();

        ApproximateMostFrequentHistogram<Slice> histogram = state.get();
        if (histogram == null) {
            state.set(otherHistogram);
        }
        else {
            histogram.merge(otherHistogram);
        }
    }

    @OutputFunction("map(T,bigint)")
    public static void output(@AggregationState LongState state, BlockBuilder out)
    {
        if (state.get() == null) {
            out.appendNull();
        }
        else {
            BlockBuilder entryBuilder = out.beginBlockEntry();
            state.get().forEachBucket((key, value) -> {
                BigintType.BIGINT.writeLong(entryBuilder, key);
                BigintType.BIGINT.writeLong(entryBuilder, value);
            });
            out.closeEntry();
        }
    }

    @OutputFunction("map(T,bigint)")
    public static void output(@AggregationState StringState state, BlockBuilder out)
    {
        if (state.get() == null) {
            out.appendNull();
        }
        else {
            BlockBuilder entryBuilder = out.beginBlockEntry();
            state.get().forEachBucket((key, value) -> {
                VarcharType.VARCHAR.writeSlice(entryBuilder, key);
                BigintType.BIGINT.writeLong(entryBuilder, value);
            });
            out.closeEntry();
        }
    }
}
