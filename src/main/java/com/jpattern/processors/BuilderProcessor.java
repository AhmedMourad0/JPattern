package com.jpattern.processors;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.jpattern.annotations.*;
import com.jpattern.constants.Pattern;
import com.squareup.javapoet.*;
import javafx.util.Pair;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BuilderProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;

    public BuilderProcessor() {
        super();
    }

    @Override
    public synchronized void init(final ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {

        return ImmutableSet.of(
                Builder.class.getCanonicalName(),
                Ignore.class.getCanonicalName(),
                Include.class.getCanonicalName(),
                Replace.class.getCanonicalName(),
                Immutable.class.getCanonicalName()
        );
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {

        for (final Element element : roundEnv.getElementsAnnotatedWith(Builder.class)) {

            if (element.getKind() != ElementKind.CLASS && element.getKind() != ElementKind.INTERFACE) {

                messager.printMessage(Diagnostic.Kind.ERROR, Builder.class.getSimpleName() + " annotation can only be applied to a class or an interface.");

                return true;
            }

            // get element metadata
            final String packageName = getPackageName(element);
            final String targetName = lowerCamelCase(element.getSimpleName().toString(), element);

            final String builderName = String.format("%sBuilder", element.getSimpleName());
            final ClassName builderType = ClassName.get(packageName, builderName);

            final long annotationsCount = element.getAnnotationMirrors()
                    .stream()
                    .filter(annotationMirror -> Pattern.getSupportedPatternsClassNames()
                            .contains(annotationMirror.getAnnotationType().toString()))
                    .limit(Pattern.values().length)
                    .count();

            validateAnnotationsParameters(element, annotationsCount);

            final boolean canIgnoreAffects = annotationsCount == 1;

            final List<VariableElement> fields = getNonIgnoredFields(element, canIgnoreAffects);

            final List<String> immutableFields = getImmutableFieldsNames(element, canIgnoreAffects);

            final Map<MethodSpec, String[]> replacements = getReplacementMethods(element, canIgnoreAffects);

            final List<MethodSpec> replacementMethods = new ArrayList<>();
            final List<String> replacedMethods = new ArrayList<>();

            replacements.forEach((method, replacedMethodsNames) -> {
                replacementMethods.add(method);
                replacedMethods.addAll(Arrays.asList(replacedMethodsNames));
            });

            // create private fields and public setters
            final List<FieldSpec> fieldsList = new ArrayList<>(fields.size());
            final List<MethodSpec> setters = new ArrayList<>(fields.size());

            for (final VariableElement field : fields) {

                final TypeName typeName = TypeName.get(field.asType());
                final String name = field.getSimpleName().toString();

                if (replacedMethods.contains(name) || immutableFields.contains(name))
                    continue;

                // create the field
                fieldsList.add(FieldSpec
                        .builder(typeName, name, Modifier.PRIVATE)
                        .initializer(field.getConstantValue().toString())
                        .build()
                );

                // create the setter
                setters.add(MethodSpec.methodBuilder(name)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(builderType)
                        .addParameter(typeName, name)
                        .addStatement("this.$N = $N", name, name)
                        .addStatement("return this")
                        .build());
            }

            final List<String> methodsNames = new ArrayList<>(fields.size());
            final List<List<ParameterSpec>> methodsParamsTypes = new ArrayList<>(fields.size());

            setters.forEach(method -> {
                methodsNames.add(method.name);
                methodsParamsTypes.add(method.parameters);
            });

            // create the build method
            final TypeName targetType = TypeName.get(element.asType());

            final MethodSpec.Builder buildMethodBuilder = MethodSpec.methodBuilder("build")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(targetType)
                    .addStatement("$1T $2N = new $1T()", targetType, targetName);

            for (final FieldSpec field : fieldsList)
                buildMethodBuilder.addStatement("$1N.$2N = this.$2N", targetName, field);

            buildMethodBuilder.addStatement("return $N", targetName);

            final MethodSpec builderMethod = MethodSpec
                    .methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(builderType)
                    .addStatement("return new $T()", builderType)
                    .build();

            final List<MethodSpec> includedMethods = getIncludedMethods(element, canIgnoreAffects);

            includedMethods.forEach(method -> {

                if (methodsNames.contains(method.name)) {

                    final int index = methodsNames.indexOf(method.name);

                    if (method.parameters.size() == methodsParamsTypes.get(index).size()) {

                        boolean isDuplicatedMethod = true;

                        for (int i = 0; i < method.parameters.size(); ++i)
                            isDuplicatedMethod = isDuplicatedMethod && methodsParamsTypes.get(index).get(i).type.equals(method.parameters.get(i).type);

                        if (isDuplicatedMethod) {

                            messager.printMessage(Diagnostic.Kind.ERROR,
                                    "Included method " +
                                            method.name +
                                            "(" +
                                            method.parameters.get(0).type +
                                            ") already exists, consider annotating with Replace instead.");
                        }
                    }
                }
            });

            // create the builder type
            final TypeSpec builder = TypeSpec.classBuilder(builderType)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addFields(fieldsList)
                    .addMethods(setters)
                    .addMethod(buildMethodBuilder.build())
                    .addMethod(builderMethod)
                    .addMethods(includedMethods)
                    .addMethods(replacementMethods)
                    .build();

            // write the java source file
            final JavaFile file = JavaFile.builder(builderType.packageName(), builder).build();

            try {

                file.writeTo(filer);

            } catch (final IOException e) {

                messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write file for element ", element);
            }
        }

        return true;
    }

    private String getPackageName(final Element element) {

        return processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
    }

    private String lowerCamelCase(final String unformattedName, final Element element) {

        String name;

        if (unformattedName.length() == 1)
            return unformattedName.toLowerCase();

        try {

            name = getCaseFormatName(unformattedName).to(CaseFormat.LOWER_CAMEL, unformattedName);

        } catch (final IllegalArgumentException e) {

            name = Character.toLowerCase(unformattedName.charAt(0)) + unformattedName.substring(1);

            messager.printMessage(Diagnostic.Kind.WARNING, "Unknown name case format ", element);
        }

        return name;
    }

    private CaseFormat getCaseFormatName(final String s) throws IllegalFormatException {

        if (s.contains("_")) {

            if (s.toUpperCase().equals(s))
                return CaseFormat.UPPER_UNDERSCORE;

            if (s.toLowerCase().equals(s))
                return CaseFormat.LOWER_UNDERSCORE;

        } else if (s.contains("-")) {

            if (s.toLowerCase().equals(s))
                return CaseFormat.LOWER_HYPHEN;

        } else {

            if (Character.isLowerCase(s.charAt(0))) {

                if (s.matches("([a-z]+[A-Z]+\\w+)+"))
                    return CaseFormat.LOWER_CAMEL;

            } else {

                if (s.matches("([A-Z]+[a-z]+\\w+)+"))
                    return CaseFormat.UPPER_CAMEL;
            }
        }

        throw new IllegalArgumentException("Couldn't find the case format of the given string.");
    }

    private void validateAnnotationsParameters(final Element element, final long count) {

        if (count > 1) {

            ElementFilter.fieldsIn(element.getEnclosedElements()).forEach(field -> {

                if (field.getAnnotation(Ignore.class) != null && field.getAnnotation(Ignore.class).affects().length == 0)
                    messager.printMessage(Diagnostic.Kind.ERROR, "Affected annotations parameter must be specified ", field);

                if (field.getAnnotation(Immutable.class) != null && field.getAnnotation(Immutable.class).affects().length == 0)
                    messager.printMessage(Diagnostic.Kind.ERROR, "Affected annotations parameter must be specified ", field);
            });

            ElementFilter.methodsIn(element.getEnclosedElements()).forEach(method -> {

                if (method.getAnnotation(Include.class) != null && method.getAnnotation(Include.class).affects().length == 0)
                    messager.printMessage(Diagnostic.Kind.ERROR, "Affected annotations parameter must be specified ", method);

                if (method.getAnnotation(Replace.class) != null && method.getAnnotation(Replace.class).affects().length == 0)
                    messager.printMessage(Diagnostic.Kind.ERROR, "Affected annotations parameter must be specified ", method);
            });
        }
    }

    private List<VariableElement> getNonIgnoredFields(final Element element, final boolean canIgnoreAffects) {

        return ElementFilter
                .fieldsIn(element.getEnclosedElements())
                .stream()
                .filter(field -> field.getAnnotation(Ignore.class) == null ||
                        (!Arrays.asList(field.getAnnotation(Ignore.class).affects()).contains(Pattern.BUILDER) &&
                                !(canIgnoreAffects && Arrays.asList(field.getAnnotation(Ignore.class).affects()).isEmpty()))
                ).collect(Collectors.toList());
    }

    private List<String> getImmutableFieldsNames(final Element element, final boolean canIgnoreAffects) {

        return ElementFilter
                .fieldsIn(element.getEnclosedElements())
                .stream()
                .filter(field -> field.getAnnotation(Immutable.class) != null &&
                        (Arrays.asList(field.getAnnotation(Immutable.class).affects()).contains(Pattern.BUILDER) ||
                                (canIgnoreAffects && Arrays.asList(field.getAnnotation(Immutable.class).affects()).isEmpty()))
                ).map(field -> field.getSimpleName().toString())
                .collect(Collectors.toList());
    }

    private List<MethodSpec> getIncludedMethods(final Element element, final boolean canIgnoreAffects) {

        return ElementFilter
                .methodsIn(element.getEnclosedElements())
                .stream()
                .filter(method -> method.getAnnotation(Include.class) != null &&
                        (Arrays.asList(method.getAnnotation(Include.class).affects()).contains(Pattern.BUILDER) ||
                                (canIgnoreAffects && Arrays.asList(method.getAnnotation(Include.class).affects()).isEmpty()))
                ).map(method -> MethodSpec
                        .methodBuilder(method.getSimpleName().toString())
                        .addModifiers(method.getModifiers())
                        .returns(TypeName.get(method.getReturnType()))
                        .addParameters(method
                                .getParameters()
                                .stream()
                                .map((Function<VariableElement, ParameterSpec>) ParameterSpec::get)
                                .collect(Collectors.toList()))
                        .addCode(method.getAnnotation(Include.class).code())
                        .build()
                ).collect(Collectors.toList());
    }

    private Map<MethodSpec, String[]> getReplacementMethods(final Element element, final boolean canIgnoreAffects) {

        return ElementFilter
                .methodsIn(element.getEnclosedElements())
                .stream()
                .filter(method -> method.getAnnotation(Replace.class) != null &&
                        (Arrays.asList(method.getAnnotation(Replace.class).affects()).contains(Pattern.BUILDER) ||
                                (canIgnoreAffects && Arrays.asList(method.getAnnotation(Replace.class).affects()).isEmpty()))
                ).map(method -> new Pair<>(MethodSpec
                        .methodBuilder(method.getSimpleName().toString())
                        .addModifiers(method.getModifiers())
                        .returns(TypeName.get(method.getReturnType()))
                        .addParameters(method
                                .getParameters()
                                .stream()
                                .map((Function<VariableElement, ParameterSpec>) ParameterSpec::get)
                                .collect(Collectors.toList()))
                        .addCode(method.getAnnotation(Replace.class).code())
                        .build(), method.getAnnotation(Replace.class).replaces())
                ).collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }
}
