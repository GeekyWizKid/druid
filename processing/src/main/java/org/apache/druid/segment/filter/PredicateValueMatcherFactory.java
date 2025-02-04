/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.filter;

import com.google.common.base.Predicate;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.data.input.Rows;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.query.filter.DruidDoublePredicate;
import org.apache.druid.query.filter.DruidFloatPredicate;
import org.apache.druid.query.filter.DruidLongPredicate;
import org.apache.druid.query.filter.DruidPredicateFactory;
import org.apache.druid.query.filter.ValueMatcher;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.BaseDoubleColumnValueSelector;
import org.apache.druid.segment.BaseFloatColumnValueSelector;
import org.apache.druid.segment.BaseLongColumnValueSelector;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnProcessorFactory;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.NilColumnValueSelector;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnType;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Creates {@link ValueMatcher} that apply a predicate to each value.
 */
public class PredicateValueMatcherFactory implements ColumnProcessorFactory<ValueMatcher>
{
  private final DruidPredicateFactory predicateFactory;

  public PredicateValueMatcherFactory(DruidPredicateFactory predicateFactory)
  {
    this.predicateFactory = predicateFactory;
  }

  @Override
  public ColumnType defaultType()
  {
    // Set default type to COMPLEX, so when the underlying type is unknown, we go into "makeComplexProcessor", which
    // uses per-row type detection.
    return ColumnType.UNKNOWN_COMPLEX;
  }

  @Override
  public ValueMatcher makeDimensionProcessor(DimensionSelector selector, boolean multiValue)
  {
    return ValueMatchers.makeStringValueMatcher(selector, predicateFactory, multiValue);
  }

  @Override
  public ValueMatcher makeFloatProcessor(BaseFloatColumnValueSelector selector)
  {
    return ValueMatchers.makeFloatValueMatcher(selector, predicateFactory);
  }

  @Override
  public ValueMatcher makeDoubleProcessor(BaseDoubleColumnValueSelector selector)
  {
    return ValueMatchers.makeDoubleValueMatcher(selector, predicateFactory);
  }

  @Override
  public ValueMatcher makeLongProcessor(BaseLongColumnValueSelector selector)
  {
    return ValueMatchers.makeLongValueMatcher(selector, predicateFactory);
  }

  @Override
  public ValueMatcher makeArrayProcessor(
      BaseObjectColumnValueSelector<?> selector,
      @Nullable ColumnCapabilities columnCapabilities
  )
  {
    if (selector instanceof NilColumnValueSelector) {
      // Column does not exist, or is unfilterable. Treat it as all nulls.

      final boolean matchesNull = predicateFactory.makeArrayPredicate(columnCapabilities).apply(null);
      if (matchesNull) {
        return ValueMatchers.allTrue();
      }
      return ValueMatchers.makeAlwaysFalseObjectMatcher(selector);
    } else {
      // use the object predicate
      final Predicate<Object[]> predicate = predicateFactory.makeArrayPredicate(columnCapabilities);
      return new ValueMatcher()
      {
        @Override
        public boolean matches(boolean includeUnknown)
        {
          final boolean matchNull = includeUnknown && predicateFactory.isNullInputUnknown();
          Object o = selector.getObject();
          if (o == null || o instanceof Object[]) {
            return (matchNull && o == null) || predicate.apply((Object[]) o);
          }
          if (o instanceof List) {
            ExprEval<?> oEval = ExprEval.bestEffortArray((List<?>) o);
            return predicate.apply(oEval.asArray());
          }
          // upcast non-array to a single element array to behave consistently with expressions.. idk if this is cool
          return predicate.apply(new Object[]{o});
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("selector", selector);
          inspector.visit("predicate", predicate);
        }
      };
    }
  }

  @Override
  public ValueMatcher makeComplexProcessor(BaseObjectColumnValueSelector<?> selector)
  {
    if (selector instanceof NilColumnValueSelector) {
      // Column does not exist, or is unfilterable. Treat it as all nulls.
      final boolean predicateMatches = predicateFactory.makeStringPredicate().apply(null);
      if (predicateMatches) {
        return ValueMatchers.allTrue();
      }
      return ValueMatchers.makeAlwaysFalseObjectMatcher(selector);
    } else if (!isNumberOrString(selector.classOfObject())) {
      // if column is definitely not a number of string, use the object predicate
      final Predicate<Object> predicate = predicateFactory.makeObjectPredicate();
      return new ValueMatcher()
      {
        @Override
        public boolean matches(boolean includeUnknown)
        {
          final boolean matchNull = includeUnknown && predicateFactory.isNullInputUnknown();
          final Object val = selector.getObject();
          return (matchNull && val == null) || predicate.apply(val);
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("selector", selector);
          inspector.visit("predicate", predicate);
        }
      };
    } else {
      // Column exists but the type of value is unknown (we might have got here because "defaultType" is COMPLEX).
      // Return a ValueMatcher that inspects the object and does type-based comparison.

      return new ValueMatcher()
      {
        private Predicate<String> stringPredicate;
        private DruidLongPredicate longPredicate;
        private DruidFloatPredicate floatPredicate;
        private DruidDoublePredicate doublePredicate;
        private Predicate<Object[]> arrayPredicate;

        @Override
        public boolean matches(boolean includeUnknown)
        {
          final Object rowValue = selector.getObject();
          final boolean matchNull = includeUnknown && predicateFactory.isNullInputUnknown();

          if (rowValue == null) {
            return matchNull || getStringPredicate().apply(null);
          } else if (rowValue instanceof Integer) {
            return getLongPredicate().applyLong((int) rowValue);
          } else if (rowValue instanceof Long) {
            return getLongPredicate().applyLong((long) rowValue);
          } else if (rowValue instanceof Float) {
            return getFloatPredicate().applyFloat((float) rowValue);
          } else if (rowValue instanceof Number) {
            // Double or some other non-int, non-long, non-float number.
            return getDoublePredicate().applyDouble(((Number) rowValue).doubleValue());
          } else if (rowValue instanceof Object[]) {
            return getArrayPredicate().apply((Object[]) rowValue);
          } else {
            // Other types. Cast to list of strings and evaluate them as strings.
            // Boolean values are handled here as well since it is not a known type in Druid.
            final List<String> rowValueStrings = Rows.objectToStrings(rowValue);

            if (rowValueStrings.isEmpty()) {
              // Empty list is equivalent to null.
              return matchNull || getStringPredicate().apply(null);
            }

            for (String rowValueString : rowValueStrings) {
              final String coerced = NullHandling.emptyToNullIfNeeded(rowValueString);
              if ((matchNull && coerced == null) || getStringPredicate().apply(coerced)) {
                return true;
              }
            }

            return false;
          }
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("selector", selector);
          inspector.visit("factory", predicateFactory);
        }

        private Predicate<String> getStringPredicate()
        {
          if (stringPredicate == null) {
            stringPredicate = predicateFactory.makeStringPredicate();
          }

          return stringPredicate;
        }

        private DruidLongPredicate getLongPredicate()
        {
          if (longPredicate == null) {
            longPredicate = predicateFactory.makeLongPredicate();
          }

          return longPredicate;
        }

        private DruidFloatPredicate getFloatPredicate()
        {
          if (floatPredicate == null) {
            floatPredicate = predicateFactory.makeFloatPredicate();
          }

          return floatPredicate;
        }

        private DruidDoublePredicate getDoublePredicate()
        {
          if (doublePredicate == null) {
            doublePredicate = predicateFactory.makeDoublePredicate();
          }

          return doublePredicate;
        }

        private Predicate<Object[]> getArrayPredicate()
        {
          if (arrayPredicate == null) {
            arrayPredicate = predicateFactory.makeArrayPredicate(null);
          }
          return arrayPredicate;
        }
      };
    }
  }

  /**
   * Returns whether a {@link BaseObjectColumnValueSelector} with object class {@code clazz} might be filterable, i.e.,
   * whether it might return numbers or strings.
   *
   * @param clazz class of object
   */
  private static <T> boolean isNumberOrString(final Class<T> clazz)
  {
    if (Number.class.isAssignableFrom(clazz) || String.class.isAssignableFrom(clazz)) {
      // clazz is a Number or String.
      return true;
    } else if (clazz.isAssignableFrom(Number.class) || clazz.isAssignableFrom(String.class)) {
      // clazz is a superclass of Number or String.
      return true;
    } else {
      // Instances of clazz cannot possibly be Numbers or Strings.
      return false;
    }
  }
}
