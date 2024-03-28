/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.cors;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;

/**
 * TBU.
 */
@FunctionalInterface
public interface CorsOriginPredicate {

    /**
     * TBU.
     */
    boolean test(String s);

    /**
     * TBU.
     */
    default Set<String> origins() {
        return ImmutableSet.of();
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate origins(String... originArr) {
        return origins(ImmutableSet.copyOf(originArr));
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate origins(Iterable<String> origins) {
        final Set<String> originSet =
                ImmutableSet.copyOf(requireNonNull(origins, "origins"))
                            .stream().map(Ascii::toLowerCase).collect(toImmutableSet());
        return new CorsOriginPredicate() {

            @Override
            public boolean test(String s) {
                return originSet.contains(Ascii.toLowerCase(s));
            }

            @Override
            public Set<String> origins() {
                return originSet;
            }
        };
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate alwaysFalse() {
        return ignored -> false;
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate ofPredicate(Predicate<String> predicate) {
        return predicate::test;
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate any() {
        return new CorsOriginPredicate() {

            @Override
            public boolean test(String s) {
                return true;
            }

            @Override
            public Set<String> origins() {
                return ImmutableSet.of("*");
            }
        };
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate regex(String pattern) {
        return regex(Pattern.compile(pattern));
    }

    /**
     * TBU.
     */
    static CorsOriginPredicate regex(Pattern pattern) {
        return s -> pattern.matcher(s).matches();
    }

    /**
     * TBU.
     */
    default CorsOriginPredicate or(CorsOriginPredicate other) {
        final CorsOriginPredicate thisPredicate = this;
        return new CorsOriginPredicate() {
            @Override
            public boolean test(String s) {
                if (thisPredicate.test(s)) {
                    return true;
                }
                return other.test(s);
            }

            @Override
            public Set<String> origins() {
                return ImmutableSet.<String>builder()
                        .addAll(thisPredicate.origins())
                        .addAll(other.origins())
                        .build();
            }
        };
    }
}
