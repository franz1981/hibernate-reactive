/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.schema;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;


import org.hibernate.reactive.BaseReactiveTest;
import org.hibernate.type.NumericBooleanConverter;
import org.hibernate.type.TrueFalseConverter;
import org.hibernate.type.YesNoConverter;
import org.hibernate.type.descriptor.java.DataHelper;

import org.junit.Test;

import io.vertx.ext.unit.TestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.reactive.containers.DatabaseConfiguration.expectedDatatype;
import static org.hibernate.reactive.containers.DatabaseConfiguration.getDatatypeQuery;

/**
 * Check that each property is mapped as the expected type in the database.
 */
public class ColumnTypesMappingTest extends BaseReactiveTest {

	@Override
	protected Collection<Class<?>> annotatedEntities() {
		return List.of( BasicTypesTestEntity.class );
	}

	private void testDatatype(TestContext context, String columnName, Class<?> type) {
		test( context, openSession()
				.thenCompose( s -> s
						.createNativeQuery( getDatatypeQuery( BasicTypesTestEntity.TABLE_NAME, columnName ), String.class )
						.getSingleResult()
						.thenAccept( typeOnTheDb -> assertThat( toString( typeOnTheDb ) ).isEqualTo( expectedDatatype( type ) ) ) )
		);
	}

	private String toString(Object result) {
		try {
			// MySQL returns a Blob
			return result instanceof Blob
					? new String( DataHelper.extractBytes( ( (Blob) result ).getBinaryStream() ) )
					: (String) result;
		}
		catch (SQLException sqlException) {
			throw new RuntimeException( sqlException );
		}
	}

	@Test
	public void testBigDecimal(TestContext context) {
		testDatatype( context, "bigDecimal", BigDecimal.class );
	}

	@Test
	public void testStringType(TestContext context) {
		testDatatype( context, "aString", String.class );
	}

	@Test
	public void testIntegerFieldType(TestContext context) {
		testDatatype( context, "fieldInteger", Integer.class );
	}

	@Test
	public void testIntegerPrimitiveType(TestContext context) {
		testDatatype( context, "primitiveInt", int.class );
	}

	@Test
	public void testBigIntegerType(TestContext context) {
		testDatatype( context, "bigInteger", BigInteger.class );
	}

	@Test
	public void testLongFieldType(TestContext context) {
		testDatatype( context, "fieldLong", Long.class );
	}

	@Test
	public void testLongPrimitiveType(TestContext context) {
		testDatatype( context, "primitiveLong", long.class );
	}

	@Test
	public void testFloatFieldType(TestContext context) {
		testDatatype( context, "fieldFloat", Float.class );
	}

	@Test
	public void testFloatPrimitiveType(TestContext context) {
		testDatatype( context, "primitiveFloat", float.class );
	}


	@Test
	public void testDoubleFieldType(TestContext context) {
		testDatatype( context, "fieldDouble", Double.class );
	}

	@Test
	public void testDoublePrimitiveType(TestContext context) {
		testDatatype( context, "primitiveDouble", double.class );
	}

	@Test
	public void testBooleanPrimitiveType(TestContext context) {
		testDatatype( context, "primitiveBoolean", boolean.class );
	}

	@Test
	public void testBooleanFieldType(TestContext context) {
		testDatatype( context, "fieldBoolean", Boolean.class );
	}

	@Test
	public void testBooleanTrueFalseType(TestContext context) {
		testDatatype( context, "booleanTrueFalse", TrueFalseConverter.class );
	}

	@Test
	public void testBooleanYesNoType(TestContext context) {
		testDatatype( context, "booleanYesNo", YesNoConverter.class );
	}

	@Test
	public void testBooleanNumericType(TestContext context) {
		testDatatype( context, "booleanNumeric", NumericBooleanConverter.class );
	}

	@Test
	public void testBytePrimitiveType(TestContext context) {
		testDatatype( context, "primitiveByte", byte.class );
	}

	@Test
	public void testBytesPrimitiveType(TestContext context) {
		testDatatype( context, "primitiveBytes", byte[].class );
	}

	@Test
	public void testByteFieldType(TestContext context) {
		testDatatype( context, "fieldByte", Byte.class );
	}

	@Test
	public void testUrlType(TestContext context) {
		testDatatype( context, "url", URL.class );
	}

	@Test
	public void testDateType(TestContext context) {
		testDatatype( context, "someDate", Date.class );
	}

	@Test
	public void testDateAsTimeType(TestContext context) {
		testDatatype( context, "dateAsTime", Time.class );
	}

	@Test
	public void testDateAsTimestampType(TestContext context) {
		testDatatype( context, "dateAsTimestamp", Timestamp.class );
	}

	@Test
	public void testTimeZoneType(TestContext context) {
		testDatatype( context, "timeZone", TimeZone.class );
	}

	@Test
	public void testCalendarAsDateType(TestContext context) {
		testDatatype( context, "calendarAsDate", Date.class );
	}

	@Test
	public void testCalendarAsTimestampType(TestContext context) {
		testDatatype( context, "calendarAsTimestamp", Timestamp.class );
	}

	@Test
	public void testLocalDateType(TestContext context) {
		testDatatype( context, "localdayte", LocalDate.class );
	}

	@Test
	public void testLocalDateTimeType(TestContext context) {
		testDatatype( context, "alocalDT", LocalDateTime.class );
	}

	@Test
	public void testSerializableType(TestContext context) {
		testDatatype( context, "serializable", Serializable.class );
	}
}
