package com.tngtech.archunit.core;

import java.util.Map;
import java.util.Objects;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.tngtech.archunit.base.ArchUnitException.ReflectionException;
import com.tngtech.archunit.base.Optional;
import org.objectweb.asm.Type;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.primitives.Primitives.allPrimitiveTypes;
import static com.tngtech.archunit.core.Formatters.ensureSimpleName;

interface JavaType {
    String getName();

    String getSimpleName();

    String getPackage();

    @ResolvesTypesViaReflection
    Class<?> resolveClass();

    @ResolvesTypesViaReflection
    Class<?> resolveClass(ClassLoader classLoader);

    Optional<JavaType> tryGetComponentType();

    boolean isPrimitive();

    boolean isArray();

    class From {
        private static final ImmutableMap<String, Class<?>> primitiveClassesByName =
                Maps.uniqueIndex(allPrimitiveTypes(), new Function<Class<?>, String>() {
                    @Override
                    public String apply(Class<?> input) {
                        return input.getName();
                    }
                });
        private static final ImmutableBiMap<String, Class<?>> primitiveClassesByDescriptor =
                ImmutableBiMap.copyOf(Maps.uniqueIndex(allPrimitiveTypes(), new Function<Class<?>, String>() {
                    @Override
                    public String apply(Class<?> input) {
                        return Type.getType(input).getDescriptor();
                    }
                }));
        private static final Map<String, Class<?>> primitiveClassesByNameOrDescriptor =
                ImmutableMap.<String, Class<?>>builder()
                        .putAll(primitiveClassesByName)
                        .putAll(primitiveClassesByDescriptor)
                        .build();

        static JavaType name(String typeName) {
            if (primitiveClassesByNameOrDescriptor.containsKey(typeName)) {
                return new PrimitiveType(Type.getType(primitiveClassesByNameOrDescriptor.get(typeName)).getClassName());
            }
            if (isArray(typeName)) {
                // NOTE: ASM uses the canonical name for arrays (i.e. java.lang.Object[]), but we want the class name,
                //       i.e. [Ljava.lang.Object;
                return new ArrayType(ensureCorrectArrayTypeName(typeName));
            }
            if (typeName.contains("/")) {
                return new ObjectType(Type.getType(typeName).getClassName());
            }
            return new ObjectType(typeName);
        }

        private static boolean isArray(String typeName) {
            return typeName.startsWith("[") || typeName.endsWith("]"); // We support class name ([Ljava.lang.Object;) and canonical name java.lang.Object[]
        }

        private static String ensureCorrectArrayTypeName(String name) {
            return name.endsWith("[]") ? convertCanonicalArrayNameToClassName(name) : name;
        }

        private static String convertCanonicalArrayNameToClassName(String name) {
            String arrayDesignator = Strings.repeat("[", CharMatcher.is('[').countIn(name));
            return arrayDesignator + createComponentTypeName(name);
        }

        private static String createComponentTypeName(String name) {
            String baseName = name.substring(0, name.indexOf("[]"));

            return primitiveClassesByName.containsKey(baseName) ?
                    createPrimitiveComponentType(baseName) :
                    createObjectComponentType(baseName);
        }

        private static String createPrimitiveComponentType(String componentTypeName) {
            return primitiveClassesByDescriptor.inverse().get(primitiveClassesByName.get(componentTypeName));
        }

        private static String createObjectComponentType(String componentTypeName) {
            return "L" + componentTypeName + ";";
        }

        /**
         * Takes an 'internal' ASM object type name, i.e. the class name but with slashes instead of periods,
         * i.e. java/lang/Object (note that this is not a descriptor like Ljava/lang/Object;)
         */
        static JavaType fromAsmObjectTypeName(String objectTypeName) {
            return asmType(Type.getObjectType(objectTypeName));
        }

        static JavaType asmType(Type type) {
            return name(type.getClassName());
        }

        static JavaType javaClass(JavaClass javaClass) {
            return name(javaClass.getName());
        }

        private abstract static class AbstractType implements JavaType {
            private final String name;
            private final String simpleName;
            private final String javaPackage;

            private AbstractType(String name, String simpleName, String javaPackage) {
                this.name = name;
                this.simpleName = simpleName;
                this.javaPackage = javaPackage;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getSimpleName() {
                return simpleName;
            }

            @Override
            public String getPackage() {
                return javaPackage;
            }

            @Override
            public Class<?> resolveClass() {
                return resolveClass(getClass().getClassLoader());
            }

            @Override
            public Class<?> resolveClass(ClassLoader classLoader) {
                try {
                    return classForName(classLoader);
                } catch (ClassNotFoundException e) {
                    throw new ReflectionException(e);
                }
            }

            @MayResolveTypesViaReflection(reason = "This method is one of the known sources for resolving via reflection")
            Class<?> classForName(ClassLoader classLoader) throws ClassNotFoundException {
                return Class.forName(getName(), false, classLoader);
            }

            @Override
            public Optional<JavaType> tryGetComponentType() {
                return Optional.absent();
            }

            @Override
            public boolean isPrimitive() {
                return false;
            }

            @Override
            public boolean isArray() {
                return false;
            }

            @Override
            public int hashCode() {
                return Objects.hash(getName());
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null || getClass() != obj.getClass()) {
                    return false;
                }
                final JavaType other = (JavaType) obj;
                return Objects.equals(this.getName(), other.getName());
            }

            @Override
            public String toString() {
                return getClass().getSimpleName() + "{" + getName() + "}";
            }
        }

        private static class ObjectType extends AbstractType {
            ObjectType(String fullName) {
                super(fullName, ensureSimpleName(fullName), createPackage(fullName));
            }

            private static String createPackage(String fullName) {
                int packageEnd = fullName.lastIndexOf('.');
                return packageEnd >= 0 ? fullName.substring(0, packageEnd) : "";
            }
        }

        private static class PrimitiveType extends AbstractType {
            PrimitiveType(String fullName) {
                super(fullName, fullName, "");
                checkArgument(primitiveClassesByName.containsKey(fullName), "'%s' must be a primitive name", fullName);
            }

            @Override
            Class<?> classForName(ClassLoader classLoader) throws ClassNotFoundException {
                return primitiveClassesByName.get(getName());
            }

            @Override
            public boolean isPrimitive() {
                return true;
            }
        }

        private static class ArrayType extends AbstractType {
            ArrayType(String fullName) {
                super(fullName, createSimpleName(fullName), "");
            }

            private static String createSimpleName(String fullName) {
                return ensureSimpleName(getCanonicalName(fullName));
            }

            private static String getCanonicalName(String fullName) {
                return Type.getType(fullName).getClassName();
            }

            @Override
            public boolean isArray() {
                return true;
            }

            @Override
            public Optional<JavaType> tryGetComponentType() {
                String canonicalName = getCanonicalName(getName());
                String componentTypeName = canonicalName.substring(0, canonicalName.lastIndexOf("["));
                return Optional.of(JavaType.From.name(componentTypeName));
            }
        }
    }
}
