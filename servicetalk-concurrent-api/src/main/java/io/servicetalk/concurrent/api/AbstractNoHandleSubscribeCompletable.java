/*
 * Copyright © 2018, 2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api;

import io.servicetalk.concurrent.api.SubscribableSources.SubscribableCompletable;

import static io.servicetalk.concurrent.internal.SubscriberUtils.deliverErrorFromSource;

/**
 * A {@link Completable} that does not expect to receive a call to {@link #handleSubscribe(Subscriber)} since it
 * overrides {@link Completable#handleSubscribe(Subscriber, CapturedContext, AsyncContextProvider)}.
 */
abstract class AbstractNoHandleSubscribeCompletable extends SubscribableCompletable {

    @Override
    protected final void handleSubscribe(final Subscriber subscriber) {
        deliverErrorFromSource(subscriber, newUnsupportedOperationException(getClass()));
    }

    static UnsupportedOperationException newUnsupportedOperationException(final Class<?> clazz) {
        return new UnsupportedOperationException(
                "Subscribe with no " + CapturedContext.class.getSimpleName() + " is not supported for " + clazz);
    }
}
