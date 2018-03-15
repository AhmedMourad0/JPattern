package com.jpattern.constants;

import com.google.common.collect.ImmutableSet;
import com.jpattern.annotations.Builder;

import java.util.Set;

public enum Pattern {

    BUILDER;

    public static Set<String> getSupportedPatternsClassNames() {
        return ImmutableSet.of(Builder.class.getName());
    }
}
