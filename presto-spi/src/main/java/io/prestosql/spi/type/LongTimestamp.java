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
package io.prestosql.spi.type;

import java.util.Objects;

public final class LongTimestamp
        implements Comparable<LongTimestamp>
{
    private static final int PICOSECONDS_PER_MICROSECOND = 1_000_000;

    private final long epochMicros;
    private final int picosOfMicro; // number of picoSeconds of the microsecond corresponding to epochMicros. It represents an increment towards the positive side.

    public LongTimestamp(long epochMicros, int picosOfMicro)
    {
        if (picosOfMicro < 0) {
            throw new IllegalArgumentException("picosOfMicro must be >= 0");
        }
        if (picosOfMicro >= PICOSECONDS_PER_MICROSECOND) {
            throw new IllegalArgumentException("picosOfMicro must be < 1_000_000");
        }
        this.epochMicros = epochMicros;
        this.picosOfMicro = picosOfMicro;
    }

    public long getEpochMicros()
    {
        return epochMicros;
    }

    public int getPicosOfMicro()
    {
        return picosOfMicro;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LongTimestamp that = (LongTimestamp) o;
        return epochMicros == that.epochMicros &&
                picosOfMicro == that.picosOfMicro;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(epochMicros, picosOfMicro);
    }

    @Override
    public int compareTo(LongTimestamp other)
    {
        int value = Long.compare(epochMicros, other.epochMicros);
        if (value != 0) {
            return value;
        }
        return Integer.compareUnsigned(picosOfMicro, other.picosOfMicro);
    }
}
