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

import io.servicetalk.concurrent.api.SubscribableSources.SubscribablePublisher;

import static io.servicetalk.concurrent.api.AbstractNoHandleSubscribeCompletable.newUnsupportedOperationException;
import static io.servicetalk.concurrent.internal.SubscriberUtils.deliverErrorFromSource;

/**
 * A {@link Publisher} that does not expect to receive a call to {@link #handleSubscribe(Subscriber)} since it overrides
 * {@link Publisher#handleSubscribe(Subscriber, CapturedContext, AsyncContextProvider)}.
 *
 * @param <T> Type of items emitted.
 */
abstract class AbstractNoHandleSubscribePublisher<T> extends SubscribablePublisher<T> {

    @Override
    protected final void handleSubscribe(final Subscriber<? super T> subscriber) {
        deliverErrorFromSource(subscriber, newUnsupportedOperationException(getClass()));
    }
}
