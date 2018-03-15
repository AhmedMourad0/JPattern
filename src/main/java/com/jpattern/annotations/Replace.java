package com.jpattern.annotations;

import com.jpattern.constants.Pattern;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Replace {

    Pattern[] affects() default {};

    String[] replaces();

    String code();
}
