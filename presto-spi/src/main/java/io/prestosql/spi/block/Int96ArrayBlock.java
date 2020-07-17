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
package io.prestosql.spi.block;

import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.util.Optional;
import java.util.function.BiConsumer;

import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.spi.block.BlockUtil.checkArrayRange;
import static io.prestosql.spi.block.BlockUtil.checkValidRegion;
import static io.prestosql.spi.block.BlockUtil.compactArray;
import static io.prestosql.spi.block.BlockUtil.countUsedPositions;

public class Int96ArrayBlock
        implements Block
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(Int96ArrayBlock.class).instanceSize();
    public static final int INT96_BYTES = Long.BYTES + Integer.BYTES;

    private final int positionOffset;
    private final int positionCount;
    @Nullable
    private final boolean[] valueIsNull;
    private final long[] high;
    private final int[] low;

    private final long sizeInBytes;
    private final long retainedSizeInBytes;

    public Int96ArrayBlock(int positionCount, Optional<boolean[]> valueIsNull, long[] high, int[] low)
    {
        this(0, positionCount, valueIsNull.orElse(null), high, low);
    }

    Int96ArrayBlock(int positionOffset, int positionCount, boolean[] valueIsNull, long[] high, int[] low)
    {
        if (positionOffset < 0) {
            throw new IllegalArgumentException("positionOffset is negative");
        }
        this.positionOffset = positionOffset;
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (high.length - positionOffset < positionCount) {
            throw new IllegalArgumentException("high length is less than positionCount");
        }
        this.high = high;

        if (low.length - positionOffset < positionCount) {
            throw new IllegalArgumentException("low length is less than positionCount");
        }
        this.low = low;

        if (valueIsNull != null && valueIsNull.length - positionOffset < positionCount) {
            throw new IllegalArgumentException("isNull length is less than positionCount");
        }
        this.valueIsNull = valueIsNull;

        sizeInBytes = (INT96_BYTES + Byte.BYTES) * (long) positionCount;
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + sizeOf(high) + sizeOf(low);
    }

    @Override
    public long getSizeInBytes()
    {
        return sizeInBytes;
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        return (INT96_BYTES + Byte.BYTES) * (long) length;
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions)
    {
        return (INT96_BYTES + Byte.BYTES) * (long) countUsedPositions(positions);
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return isNull(position) ? 0 : INT96_BYTES;
    }

    @Override
    public void retainedBytesForEachPart(BiConsumer<Object, Long> consumer)
    {
        consumer.accept(high, sizeOf(high));
        consumer.accept(low, sizeOf(low));
        if (valueIsNull != null) {
            consumer.accept(valueIsNull, sizeOf(valueIsNull));
        }
        consumer.accept(this, (long) INSTANCE_SIZE);
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getLong(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be 0");
        }
        return high[positionOffset + position];
    }

    @Override
    public int getInt(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 8) {
            throw new IllegalArgumentException("offset must be 8");
        }
        return low[positionOffset + position];
    }

    @Override
    public boolean mayHaveNull()
    {
        return valueIsNull != null;
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(position);
        return valueIsNull != null && valueIsNull[position + positionOffset];
    }

    @Override
    public void writePositionTo(int position, BlockBuilder blockBuilder)
    {
        checkReadablePosition(position);
        blockBuilder.writeLong(high[position + positionOffset]);
        blockBuilder.writeInt(low[position + positionOffset]);
        blockBuilder.closeEntry();
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(position);
        return new Int96ArrayBlock(
                0,
                1,
                isNull(position) ? new boolean[] {true} : null,
                new long[] {high[position + positionOffset]},
                new int[] {low[position + positionOffset]});
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        boolean[] newValueIsNull = null;
        if (valueIsNull != null) {
            newValueIsNull = new boolean[length];
        }
        long[] newHigh = new long[length];
        int[] newLow = new int[length];
        for (int i = 0; i < length; i++) {
            int position = positions[offset + i];
            checkReadablePosition(position);
            if (valueIsNull != null) {
                newValueIsNull[i] = valueIsNull[position + positionOffset];
            }
            newHigh[i] = high[position + positionOffset];
            newLow[i] = low[position + positionOffset];
        }
        return new Int96ArrayBlock(0, length, newValueIsNull, newHigh, newLow);
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        return new Int96ArrayBlock(positionOffset + this.positionOffset, length, valueIsNull, high, low);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        positionOffset += this.positionOffset;
        boolean[] newValueIsNull = valueIsNull == null ? null : compactArray(valueIsNull, positionOffset, length);
        long[] newHigh = compactArray(high, positionOffset, length);
        int[] newLow = compactArray(low, positionOffset, length);

        if (newValueIsNull == valueIsNull && newHigh == high && newLow == low) {
            return this;
        }
        return new Int96ArrayBlock(0, length, newValueIsNull, newHigh, newLow);
    }

    @Override
    public String getEncodingName()
    {
        return Int96ArrayBlockEncoding.NAME;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("Int96ArrayBlock{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }

    private void checkReadablePosition(int position)
    {
        if (position < 0 || position >= getPositionCount()) {
            throw new IllegalArgumentException("position is not valid");
        }
    }
}
