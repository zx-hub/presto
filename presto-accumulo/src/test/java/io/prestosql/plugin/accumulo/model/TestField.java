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
package io.prestosql.plugin.accumulo.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.prestosql.plugin.accumulo.serializers.AccumuloRowSerializer;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignatureParameter;
import org.testng.annotations.Test;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.GregorianCalendar;

import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimeType.TIME;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.lang.Float.floatToIntBits;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;

public class TestField
{
    @Test(expectedExceptions = NullPointerException.class, expectedExceptionsMessageRegExp = "type is null")
    public void testTypeIsNull()
    {
        new Field(null, null);
    }

    @Test
    public void testArray()
    {
        Type type = new ArrayType(VARCHAR);
        Block expected = AccumuloRowSerializer.getBlockFromArray(VARCHAR, ImmutableList.of("a", "b", "c"));
        Field f1 = new Field(expected, type);
        assertEquals(f1.getArray(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testBoolean()
    {
        Type type = BOOLEAN;
        Field f1 = new Field(true, type);
        assertEquals(f1.getBoolean().booleanValue(), true);
        assertEquals(f1.getObject(), true);
        assertEquals(f1.getType(), type);

        f1 = new Field(false, type);
        assertEquals(f1.getBoolean().booleanValue(), false);
        assertEquals(f1.getObject(), false);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testDate()
    {
        Type type = DATE;
        Date expected = new Date(new GregorianCalendar(1999, 0, 1).getTime().getTime());
        Field f1 = new Field(10592L, type);
        assertEquals(f1.getDate(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testDouble()
    {
        Type type = DOUBLE;
        Double expected = 123.45678;
        Field f1 = new Field(expected, type);
        assertEquals(f1.getDouble(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testFloat()
    {
        Type type = REAL;
        Float expected = 123.45678f;
        Field f1 = new Field((long) floatToIntBits(expected), type);
        assertEquals(f1.getFloat(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testInt()
    {
        Type type = INTEGER;
        Integer expected = 12345678;
        Field f1 = new Field((long) expected, type);
        assertEquals(f1.getInt(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testLong()
    {
        Type type = BIGINT;
        Long expected = 12345678L;
        Field f1 = new Field(expected, type);
        assertEquals(f1.getLong(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testMap()
    {
        Type type = createTestMetadataManager().getParameterizedType(StandardTypes.MAP, ImmutableList.of(
                TypeSignatureParameter.typeParameter(VARCHAR.getTypeSignature()),
                TypeSignatureParameter.typeParameter(BIGINT.getTypeSignature())));
        Block expected = AccumuloRowSerializer.getBlockFromMap(type, ImmutableMap.of("a", 1L, "b", 2L, "c", 3L));
        Field f1 = new Field(expected, type);
        assertEquals(f1.getMap(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);
    }

    @Test
    public void testSmallInt()
    {
        Type type = SMALLINT;
        Short expected = 12345;
        Field f1 = new Field((long) expected, type);
        assertEquals(f1.getShort(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testTime()
    {
        Type type = TIME;
        Time expected = new Time(new GregorianCalendar(1970, 0, 1, 12, 30, 0).getTime().getTime());
        Field f1 = new Field(70200000L, type);
        assertEquals(f1.getTime(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testTimestamp()
    {
        Type type = TIMESTAMP;
        Timestamp expected = new Timestamp(new GregorianCalendar(1999, 0, 1, 12, 30, 0).getTime().getTime());
        Field f1 = new Field(915219000000L, type);
        assertEquals(f1.getTimestamp(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testTinyInt()
    {
        Type type = TINYINT;
        Byte expected = 123;
        Field f1 = new Field((long) expected, type);
        assertEquals(f1.getByte(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testVarbinary()
    {
        Type type = VARBINARY;
        byte[] expected = "O'Leary".getBytes(UTF_8);
        Field f1 = new Field(Slices.wrappedBuffer(expected.clone()), type);
        assertEquals(f1.getVarbinary(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }

    @Test
    public void testVarchar()
    {
        Type type = VARCHAR;
        String expected = "O'Leary";
        Field f1 = new Field(utf8Slice(expected), type);
        assertEquals(f1.getVarchar(), expected);
        assertEquals(f1.getObject(), expected);
        assertEquals(f1.getType(), type);

        Field f2 = new Field(f1);
        assertEquals(f2, f1);
    }
}
