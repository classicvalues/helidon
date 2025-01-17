/*
 * Copyright (c) 2018, 2021 Oracle and/or its affiliates.
 *
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

package io.helidon.microprofile.metrics;

import java.time.Duration;

import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.Timed;

/**
 * Interceptor for {@link Timed} annotation.
 */
@Timed
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 10)
final class InterceptorTimed extends InterceptorTimedBase<Timer> {

    InterceptorTimed() {
        super(Timed.class, Timer.class);
    }

    @Override
    void postComplete(Timer metric) {
        metric.update(Duration.ofNanos(durationNanoseconds()));
    }
}
