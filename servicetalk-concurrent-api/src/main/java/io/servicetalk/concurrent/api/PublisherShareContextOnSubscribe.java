/*
 * Copyright © 2019, 2021 Apple Inc. and the ServiceTalk project authors
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

final class PublisherShareContextOnSubscribe<T> extends AbstractNoHandleSubscribePublisher<T> {
    private final Publisher<T> original;

    PublisherShareContextOnSubscribe(final Publisher<T> original) {
        this.original = original;
    }

    @Override
    CapturedContext contextForSubscribe(AsyncContextProvider provider) {
        return provider.captureContext();
    }

    @Override
    void handleSubscribe(final Subscriber<? super T> singleSubscriber,
                         final CapturedContext capturedContext, final AsyncContextProvider contextProvider) {
        // This operator currently only targets the subscribe method. Given this limitation if we try to change the
        // ContextMap now it is possible that operators downstream in the subscribe call stack may have modified
        // the ContextMap and we don't want to discard those changes by using a different ContextMap.
        original.delegateSubscribe(singleSubscriber, capturedContext, contextProvider);
    }
}
