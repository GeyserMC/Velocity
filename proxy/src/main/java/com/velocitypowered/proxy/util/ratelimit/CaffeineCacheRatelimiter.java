/*
 * Copyright (C) 2018-2021 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.util.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

/**
 * A simple rate-limiter based on a Caffeine {@link Cache}.
 */
public class CaffeineCacheRatelimiter<T> implements Ratelimiter<T> {

  private final Cache<T, Long> expiringCache;
  private final long timeoutNanos;

  CaffeineCacheRatelimiter(long time, TimeUnit unit) {
    this(time, unit, Ticker.systemTicker());
  }

  @VisibleForTesting
  CaffeineCacheRatelimiter(long time, TimeUnit unit, Ticker ticker) {
    Preconditions.checkNotNull(unit, "unit");
    Preconditions.checkNotNull(ticker, "ticker");
    this.timeoutNanos = unit.toNanos(time);
    this.expiringCache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(time, unit)
        .build();
  }

  /**
   * Attempts to rate-limit the object.
   *
   * @param key the object to rate limit
   * @return true if we should allow the object, false if we should rate-limit
   */
  @Override
  public boolean attempt(@NotNull  T key) {
    long expectedNewValue = System.nanoTime() + timeoutNanos;
    long last = expiringCache.get(key, (key1) -> expectedNewValue);
    return expectedNewValue == last;
  }
}
