package com.xtremelabs.robolectric.bytecode;

import javassist.*;

public class MethodGenerator {
    public static final String CONSTRUCTOR_METHOD_NAME = "__constructor__";

    private final CtClass ctClass;

    public MethodGenerator(CtClass ctClass) {
        this.ctClass = ctClass;
    }

    public void fixConstructors() throws CannotCompileException, NotFoundException {

        if (ctClass.isEnum()) {
            // skip enum constructors because they are not stubs in android.jar
            return;
        }

        boolean hasDefault = false;

        for (CtConstructor ctConstructor : ctClass.getDeclaredConstructors()) {
            try {
                fixConstructor(ctConstructor);

                if (ctConstructor.getParameterTypes().length == 0) {
                    hasDefault = true;
                }
            } catch (Exception e) {
                throw new RuntimeException("problem instrumenting " + ctConstructor, e);
            }
        }

        if (!hasDefault) {
            String methodBody = generateConstructorBody(new CtClass[0]);
            CtConstructor defaultConstructor = CtNewConstructor.make(new CtClass[0], new CtClass[0], "{\n" + methodBody + "}\n", ctClass);
            ctClass.addConstructor(defaultConstructor);
        }
    }

    public void fixConstructor(CtConstructor ctConstructor) throws NotFoundException, CannotCompileException {
        String methodBody = generateConstructorBody(ctConstructor.getParameterTypes());
        ctConstructor.setBody("{\n" + methodBody + "}\n");
    }

    public String generateConstructorBody(CtClass[] parameterTypes) throws NotFoundException {
        return generateMethodBody(
                new CtMethod(CtClass.voidType, "<init>", parameterTypes, ctClass),
                CtClass.voidType,
                Type.VOID,
                false,
                false);
    }

    public void fixMethods() throws NotFoundException, CannotCompileException {
        for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            fixMethod(ctMethod, true);
        }
        CtMethod equalsMethod = ctClass.getMethod("equals", "(Ljava/lang/Object;)Z");
        CtMethod hashCodeMethod = ctClass.getMethod("hashCode", "()I");
        CtMethod toStringMethod = ctClass.getMethod("toString", "()Ljava/lang/String;");

        fixMethod(equalsMethod, false);
        fixMethod(hashCodeMethod, false);
        fixMethod(toStringMethod, false);
    }

    public void fixMethod(final CtMethod ctMethod, boolean isDeclaredOnClass) throws NotFoundException {
        String describeBefore = describe(ctMethod);
        try {
            CtClass declaringClass = ctMethod.getDeclaringClass();
            int originalModifiers = ctMethod.getModifiers();

            boolean wasNative = Modifier.isNative(originalModifiers);
            boolean wasFinal = Modifier.isFinal(originalModifiers);
            boolean wasAbstract = Modifier.isAbstract(originalModifiers);
            boolean wasDeclaredInClass = ctClass == declaringClass;

            if (wasFinal && ctClass.isEnum()) {
                return;
            }

            int newModifiers = originalModifiers;
            if (wasNative) {
                newModifiers = Modifier.clear(newModifiers, Modifier.NATIVE);
            }
            if (wasFinal) {
                newModifiers = Modifier.clear(newModifiers, Modifier.FINAL);
            }
            if (isDeclaredOnClass) {
                ctMethod.setModifiers(newModifiers);
            }

            CtClass returnCtClass = ctMethod.getReturnType();
            Type returnType = Type.find(returnCtClass);

            String methodName = ctMethod.getName();
            CtClass[] paramTypes = ctMethod.getParameterTypes();

//            if (!isAbstract) {
//                if (methodName.startsWith("set") && paramTypes.length == 1) {
//                    String fieldName = "__" + methodName.substring(3);
//                    if (declareField(ctClass, fieldName, paramTypes[0])) {
//                        methodBody = fieldName + " = $1;\n" + methodBody;
//                    }
//                } else if (methodName.startsWith("get") && paramTypes.length == 0) {
//                    String fieldName = "__" + methodName.substring(3);
//                    if (declareField(ctClass, fieldName, returnType)) {
//                        methodBody = "return " + fieldName + ";\n";
//                    }
//                }
//            }

            boolean isStatic = Modifier.isStatic(originalModifiers);
            String methodBody = generateMethodBody(ctMethod, wasNative, wasAbstract, returnCtClass, returnType, isStatic, !isDeclaredOnClass);

            if (!isDeclaredOnClass) {
                CtMethod newMethod = makeNewMethod(ctMethod, returnCtClass, methodName, paramTypes, "{\n" + methodBody + generateCallToSuper(ctMethod) + "\n}");
                newMethod.setModifiers(newModifiers);
                if (wasDeclaredInClass) {
                    ctMethod.insertBefore("{\n" + methodBody + "}\n");
                } else {
                    ctClass.addMethod(newMethod);
                }
            } else if (wasAbstract || wasNative) {
                CtMethod newMethod = makeNewMethod(ctMethod, returnCtClass, methodName, paramTypes, "{\n" + methodBody + "\n}");
                ctMethod.setBody(newMethod, null);
            } else {
                ctMethod.insertBefore("{\n" + methodBody + "}\n");
            }
        } catch (Exception e) {
            throw new RuntimeException("problem instrumenting " + describeBefore, e);
        }
    }

    public static String describe(CtMethod ctMethod) throws NotFoundException {
        return Modifier.toString(ctMethod.getModifiers()) + " " + ctMethod.getReturnType().getSimpleName() + " " + ctMethod.getLongName();
    }

    public CtMethod makeNewMethod(CtMethod ctMethod, CtClass returnCtClass, String methodName, CtClass[] paramTypes, String methodBody) throws CannotCompileException, NotFoundException {
        return CtNewMethod.make(
                ctMethod.getModifiers(),
                returnCtClass,
                methodName,
                paramTypes,
                ctMethod.getExceptionTypes(),
                methodBody,
                ctClass);
    }

    public String generateCallToSuper(CtMethod ctMethod) throws NotFoundException {
        return "return super." + ctMethod.getName() + "(" + makeParameterReplacementList(ctMethod.getParameterTypes().length) + ");";
    }

    public String makeParameterReplacementList(int length) {
        if (length == 0) {
            return "";
        }

        String parameterReplacementList = "$1";
        for (int i = 2; i <= length; ++i) {
            parameterReplacementList += ", $" + i;
        }
        return parameterReplacementList;
    }

    public String generateMethodBody(CtMethod ctMethod, boolean wasNative, boolean wasAbstract, CtClass returnCtClass, Type returnType, boolean aStatic, boolean shouldGenerateCallToSuper) throws NotFoundException {
        String methodBody;
        if (wasAbstract) {
            methodBody = returnType.isVoid() ? "" : "return " + returnType.defaultReturnString() + ";";
        } else {
            methodBody = generateMethodBody(ctMethod, returnCtClass, returnType, aStatic, shouldGenerateCallToSuper);
        }

        if (wasNative) {
            methodBody += returnType.isVoid() ? "" : "return " + returnType.defaultReturnString() + ";";
        }
        return methodBody;
    }

    public String generateMethodBody(CtMethod ctMethod, CtClass returnCtClass, Type returnType, boolean isStatic, boolean shouldGenerateCallToSuper) throws NotFoundException {
        boolean returnsVoid = returnType.isVoid();
        String className = ctClass.getName();

        /*
            METHOD BODY TEMPLATE:

            if (!RobolectricInternals.shouldCallDirectly(isStatic ? class : this)) {
                Object x = RobolectricInternals.methodInvoked(
                    <className>.class, "<methodName>", isStatic ? null : this,
                    <paramTypes>,
                    <params>
                );
                if (x != null) {
                    return ((<returnClass>)x)<unboxing>;
                }
                <optional super call or return default (null/0)>;
            }

        */

        String methodBody;
        StringBuilder buf = new StringBuilder();
        buf.append("if (!");
        generateCallToShouldCallDirectory(isStatic, className, buf);
        buf.append(") {\n");

        if (!returnsVoid) {
            buf.append("Object x = ");
        }
        generateCallToMethodInvoked(ctMethod, isStatic, className, buf);

        if (!returnsVoid) {
            buf.append("if (x != null) return ((");
            buf.append(returnType.nonPrimitiveClassName(returnCtClass));
            buf.append(") x)");
            buf.append(returnType.unboxString());
            buf.append(";\n");
            if (shouldGenerateCallToSuper) {
                buf.append(generateCallToSuper(ctMethod));
            } else {
                buf.append("return ");
                buf.append(returnType.defaultReturnString());
                buf.append(";\n");
            }
        } else {
            buf.append("return;\n");
        }

        buf.append("}\n");

        methodBody = buf.toString();
        return methodBody;
    }

    public void generateCallToShouldCallDirectory(boolean isStatic, String className, StringBuilder buf) {
        buf.append(RobolectricInternals.class.getName());
        buf.append(".shouldCallDirectly(");
        buf.append(isStatic ? className + ".class" : "this");
        buf.append(")");
    }

    public void generateCallToMethodInvoked(CtMethod ctMethod, boolean isStatic, String className, StringBuilder buf) throws NotFoundException {
        buf.append(RobolectricInternals.class.getName());
        buf.append(".methodInvoked(\n  ");
        buf.append(className);
        buf.append(".class, \"");
        buf.append(ctMethod.getName());
        buf.append("\", ");
        if (!isStatic) {
            buf.append("this");
        } else {
            buf.append("null");
        }
        buf.append(", ");

        appendParamTypeArray(buf, ctMethod);
        buf.append(", ");
        appendParamArray(buf, ctMethod);

        buf.append(")");
        buf.append(";\n");
    }

    public void appendParamTypeArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        CtClass[] parameterTypes = ctMethod.getParameterTypes();
        if (parameterTypes.length == 0) {
            buf.append("new String[0]");
        } else {
            buf.append("new String[] {");
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append("\"");
                CtClass parameterType = parameterTypes[i];
                buf.append(parameterType.getName());
                buf.append("\"");
            }
            buf.append("}");
        }
    }

    public void appendParamArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        int parameterCount = ctMethod.getParameterTypes().length;
        if (parameterCount == 0) {
            buf.append("new Object[0]");
        } else {
            buf.append("new Object[] {");
            for (int i = 0; i < parameterCount; i++) {
                if (i > 0) buf.append(", ");
                buf.append(RobolectricInternals.class.getName());
                buf.append(".autobox(");
                buf.append("$").append(i + 1);
                buf.append(")");
            }
            buf.append("}");
        }
    }
}