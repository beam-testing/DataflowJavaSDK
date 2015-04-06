/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.transforms.windowing;

import com.google.cloud.dataflow.sdk.coders.InstantCoder;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.AtMostOnceTrigger;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;

import org.joda.time.Instant;

/**
 * {@code AfterProcessingTime} triggers fire based on the current processing time. They operate in
 * the real-time domain.
 *
 * @param <W> {@link BoundedWindow} subclass used to represent the windows used
 */
public class AfterProcessingTime<W extends BoundedWindow>
    extends TimeTrigger<W, AfterProcessingTime<W>> implements AtMostOnceTrigger<W>{

  private static final long serialVersionUID = 0L;

  private static final CodedTupleTag<Instant> DELAYED_UNTIL_TAG =
      CodedTupleTag.of("delayed-until", InstantCoder.of());

  private AfterProcessingTime(SerializableFunction<Instant, Instant> delayFunction) {
    super(delayFunction);
  }

  /**
   * Creates a trigger that fires when the current processing time passes the processing time at
   * which this trigger saw the first element in a pane.
   */
  public static <W extends BoundedWindow> AfterProcessingTime<W> pastFirstElementInPane() {
    return new AfterProcessingTime<W>(IDENTITY);
  }

  @Override
  protected AfterProcessingTime<W> newWith(SerializableFunction<Instant, Instant> transform) {
    return new AfterProcessingTime<W>(transform);
  }

  @Override
  public TriggerResult onElement(
      TriggerContext<W> c, Object value, Instant timestamp, W window, WindowStatus status)
      throws Exception {
    Instant delayUntil = c.lookup(DELAYED_UNTIL_TAG, window);
    if (delayUntil == null) {
      delayUntil = computeTargetTimestamp(c.currentProcessingTime());
      c.setTimer(window, delayUntil, TimeDomain.PROCESSING_TIME);
      c.store(DELAYED_UNTIL_TAG, window, delayUntil);
    }

    return TriggerResult.CONTINUE;
  }

  @Override
  public TriggerResult onMerge(TriggerContext<W> c, Iterable<W> oldWindows, W newWindow)
      throws Exception {
    // To have gotten here, we must not have fired in any of the oldWindows.
    Instant earliestTimer = BoundedWindow.TIMESTAMP_MAX_VALUE;
    for (Instant delayedUntil : c.lookup(DELAYED_UNTIL_TAG, oldWindows).values()) {
      if (delayedUntil != null && delayedUntil.isBefore(earliestTimer)) {
        earliestTimer = delayedUntil;
      }
    }

    if (earliestTimer != null) {
      c.store(DELAYED_UNTIL_TAG, newWindow, earliestTimer);
      c.setTimer(newWindow, earliestTimer, TimeDomain.PROCESSING_TIME);
    }

    return TriggerResult.CONTINUE;
  }

  @Override
  public TriggerResult onTimer(TriggerContext<W> c, TriggerId<W> triggerId) throws Exception {
    return TriggerResult.FIRE_AND_FINISH;
  }

  @Override
  public void clear(TriggerContext<W> c, W window) throws Exception {
    c.remove(DELAYED_UNTIL_TAG, window);
    c.deleteTimer(window, TimeDomain.PROCESSING_TIME);
  }

  @Override
  public boolean willNeverFinish() {
    return false;
  }
}