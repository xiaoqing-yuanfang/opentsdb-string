// This file is part of OpenTSDB.
// Copyright (C) 2017  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.data.iterators;

import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Deferred;

import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.query.context.QueryContext;

/**
 * An abstract class used for iterating over a set of data for a single
 * time series of a single data type. In order to use an iterator properly, it
 * must be tied to a {@link QueryContext}.
 * <p>
 * TODO - doc usage
 * <p>
 * <b>Thread-Safety:</b> This iterator is <i>not</i> thread safe. Do not call 
 * methods on iterators across multiple threads. If multiple threads require a
 * view into the same data, use {@link #getCopy(QueryContext)} to retrieve a 
 * separate view of the same data that may be iterated separately on a different 
 * thread.
 * <p>
 * <b>Mutability:</b> Data returned from the iterator is immutable (so that we
 * can provide a copy of the iterator). 
 * 
 * @param <T> A {@link net.opentsdb.data.TimeSeriesDataType} data type that this 
 * iterator handles.
 * 
 * @since 3.0
 */
public abstract class TimeSeriesIterator<T extends TimeSeriesDataType> {
  
  /** The context this iterator belongs to. May be null. */
  protected QueryContext context;
  
  /** The source of data for this iterator. Purposely wildcarded so that we 
   * could write a type converter. */
  protected TimeSeriesIterator<? extends TimeSeriesDataType> source;
  
  /** A link to the parent iterator if this is a source feeding into another. 
   * Purposely wildcarded so we can write type converters.*/
  protected TimeSeriesIterator<? extends TimeSeriesDataType> parent;
  
  /** Default ctor with everything initialized to null. */
  public TimeSeriesIterator() {
    
  }
  
  /** 
   * Ctor for setting the context and registering with it.
   * @param context A context to register with.
   */
  public TimeSeriesIterator(final QueryContext context) {
    setContext(context);
  }
  
  /**
   * Ctor for iterators that consume from other iterators.
   * @param context A context to register with.
   * @param source A non-null source to consume from.
   * @throws IllegalArgumentException if the source was null.
   */
  public TimeSeriesIterator(final QueryContext context, 
      final TimeSeriesIterator<? extends TimeSeriesDataType> source) {
    if (source == null) {
      throw new IllegalArgumentException("Source cannot be null.");
    }
    if (!source.type().equals(type())) {
      throw new IllegalArgumentException("Source type [" + source.type() 
        + "] is not the same as our type [" + type() + "]");
    }
    setContext(context);
    this.source = source;
    source.setParent(this);
  }
  
  /**
   * Initializes the iterator if initialization is required. E.g. it may load
   * the first chunk of data or setup the underlying data store. This should be
   * called before any other operations on the iterator (aside from {@link #type()}.
   * <b>Requirement:</b> This method may be called more than once before {@link #next()} 
   * thus subsequent calls must be no-ops.
   * @return A Deferred resolving to a null on success or an exception if 
   * initialization failed.
   */
  public Deferred<Object> initialize() {
    return source != null ? source.initialize() : 
      Deferred.fromError(new UnsupportedOperationException("Not implemented"));
  }
  
  /**
   * The {@link TimeSeriesDataType} data type of this data returned by this iterator.
   * @return A non-null type token.
   */
  public abstract TypeToken<? extends TimeSeriesDataType> type();
  
  /**
   * A reference to the time series ID associated with all data points returned
   * by this iterator.
   * @return A non-null {@link TimeSeriesId}.
   */
  public TimeSeriesId id() {
    if (source != null) {
      return source.id();
    }
    throw new UnsupportedOperationException("Not implemented");
  }
  
  /**
   * Sets the context this iterator belongs to. 
   * If the context was already set, this method unregisters from it and registers
   * with the new context if it isn't null.
   * @param context A context, may be null.
   */
  public void setContext(final QueryContext context) {
    if (this.context != null && this.context != context) {
      this.context.unregister(this);
    }
    this.context = context;
    if (context != null) {
      context.register(this);
    }
  }
  
  /**
   * Returns the next value for this iterator. Behavior depends on whether the
   * iterator is in stand-alone mode or a part of a processor.
   * <b>NOTE:</b> The value returned may be mutated after the next call to 
   * {@link #next()} so make sure to call {@link TimeSeriesValue#getCopy()} if
   * the value must remain in memory unmutated.
   * @return A non-null value.
   * @throws IllegalStateException if the iterator is not ready to return another
   * value.
   */
  public abstract TimeSeriesValue<T> next();
  
  /**
   * If {@link IteratorStatus#END_OF_CHUNK} was returned via the last 
   * context advance call, this method will fetch the next set of data 
   * asynchronously. The following status may have data, be end of stream
   * or have an exception. 
   * @return A Deferred resolving to a null if the next chunk was fetched 
   * successfully or an exception if fetching data failed.
   */
  public Deferred<Object> fetchNext() {
    return source != null ? source.fetchNext() : 
      Deferred.fromError(new UnsupportedOperationException("Not implemented"));
  }
  
  /**
   * Creates and returns a deep copy of this iterator for another view on the 
   * time series. This allows for multi-pass operations over data by associating
   * iterators with a separate context and iterating over them without effecting
   * the parent context.
   * <p>Requirements:
   * <ul>
   * <li>The copy must return a new view of the underlying data. If this method
   * was called in the middle of iteration, the copy must start at the top of
   * the beginning of the data and the original iterator left in it's current 
   * state.</li>
   * <li>If the source iterator has not been initialized, the copy will not
   * be initialized either. Likewise if the source <i>has</i> been initialized
   * then the copy will have been as well.</li>
   * <li>The {@code parent} of the returned copy must be {@code null}. The caller
   * should set the parent if desired.</li>
   * <li>The copy's {@code context} must be set to the next context.</li>
   * </ul>
   * @param context A context for the iterator to associate with.
   * @return A non-null copy of the iterator.
   */
  public abstract TimeSeriesIterator<T> getCopy(final QueryContext context);

  /**
   * Sets the parent for this iterator.
   * @param parent A parent iterator. May be null if uncoupling.
   */
  public void setParent(final TimeSeriesIterator<?> parent) {
    this.parent = parent;
  }
  
  /**
   * Closes and releases any resources held by this iterator. If this iterator
   * is a copy, the method is a no-op.
   * @return A deferred resolving to a null on success, an exception on failure.
   */
  public Deferred<Object> close() {
    return source != null ? source.close() : 
      Deferred.fromError(new UnsupportedOperationException("Not implemented"));
  }
}