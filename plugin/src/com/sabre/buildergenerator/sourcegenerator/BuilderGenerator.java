/**
 * Copyright (c) 2009-2010 fluent-builder-generator for Eclipse commiters.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sabre Polska sp. z o.o. - initial implementation during Hackday
 */

package com.sabre.buildergenerator.sourcegenerator;

import com.sabre.buildergenerator.Activator;
import com.sabre.buildergenerator.signatureutils.SignatureResolver;
import com.sabre.buildergenerator.sourcegenerator.TypeHelper.MethodInspector;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;


public class BuilderGenerator {
    static final String SETTER_PREFIX = "set";

    private final Set<String> typesAlradyGeneratedInnerBuilders = new HashSet<String>();
    private final Set<String> typesToGenerateInnerBuilders = new HashSet<String>();

    private String getFullyQualifiedTypeName(IType type) {
        String packagePart = type.getPackageFragment().getElementName();
        String typeName = type.getElementName();

        if (packagePart.length() > 0) {
            return packagePart + "." + typeName;
        } else {
            return typeName;
        }
    }

    public String generateSource(final IType type, String packageName, String builderName, String[] fieldNames,
        String setterPrefix, String collectionSetterPrefix, String endPrefix, boolean doFormat) throws Exception {

        final BuilderSourceGenerator generator = new BuilderSourceGenerator();

        generator.setSetterPrefix(setterPrefix);
        generator.setCollectionElementSetterPrefix(collectionSetterPrefix);
        generator.setEndPrefix(endPrefix);

        StringWriter sw = new StringWriter();

        generator.setOut(new PrintWriter(sw));

        generator.addBuilderClass(getFullyQualifiedTypeName(type), packageName, builderName);
        typesToGenerateInnerBuilders.add(Signature.createTypeSignature(type.getFullyQualifiedName(), true));

        generateBuilderBaseClasses(generator, type);
        sw.flush();

        String builderSource = sw.toString();

        if (doFormat) {
            builderSource = formatSource(builderSource);
        }

        return builderSource;
    }

    private String formatSource(String builderSource) {
        TextEdit text = ToolFactory.createCodeFormatter(null).format(CodeFormatter.K_COMPILATION_UNIT, builderSource, 0,
                builderSource.length(), 0, "\n");

        // text is null if source cannot be formatted
        if (text != null) {
            Document simpleDocument = new Document(builderSource);

            try {
                text.apply(simpleDocument);
            } catch (MalformedTreeException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (BadLocationException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } finally {
                builderSource = simpleDocument.get();
            }
        }

        return builderSource;
    }

    private void addTypeToGenerateInnerBuilder(String elementTypeSignature) {
        if (!typesAlradyGeneratedInnerBuilders.contains(elementTypeSignature)) {
            typesToGenerateInnerBuilders.add(elementTypeSignature);
        }
    }

    private void generateBuilderBaseClasses(final BuilderSourceGenerator generator, final IType enclosingType)
        throws Exception {
        while (!typesToGenerateInnerBuilders.isEmpty()) {
            String typeSignature = typesToGenerateInnerBuilders.iterator().next();
            String type = SignatureResolver.signatureToTypeName(SignatureResolver.resolveSignature(enclosingType,
                        typeSignature));

            generator.startBuilderBaseClass(type); // following methods might add elements to typesUsed

            final IType resolvedType = SignatureResolver.resolveType(enclosingType, typeSignature);

            final Set<String> fieldNamesSet = null; //fieldNames != null && fieldNames.length > 0 ? new HashSet<String>(Arrays.asList(fieldNames)) : null;

            TypeHelper.findSetterMethods(resolvedType, new MethodInspector() {
                    public void nextMethod(IType methodOwnerType, IMethod method,
                        Map<String, String> parameterSubstitution) throws Exception {
                        Activator.getDefault().getLog().log(new Status(IStatus.OK, Activator.PLUGIN_ID, "nextMethod method=" + method.getElementName() + " type=" + methodOwnerType.getElementName()));

                        String fieldName = fieldnameFromSetter(method);

                        if (fieldNamesSet == null || fieldNamesSet.contains(fieldName)) {
                            try {
                                String parameterTypeSignature = method.getParameterTypes()[0];
                                String qualifiedParameterTypeSignature = SignatureResolver.resolveTypeWithParameterMapping(
                                        methodOwnerType, parameterTypeSignature, parameterSubstitution);
                                String parameterType = SignatureResolver.signatureToTypeName(
                                        qualifiedParameterTypeSignature);

                                String[] exceptionTypes;

                                exceptionTypes = method.getExceptionTypes();

                                for (int i = 0; i < exceptionTypes.length; i++) {
                                    exceptionTypes[i] = SignatureResolver.resolveTypeWithParameterMapping(methodOwnerType,
                                        exceptionTypes[i], parameterSubstitution);
                                    exceptionTypes[i] = SignatureResolver.signatureToTypeName(exceptionTypes[i]);
                                }

                                generateSimpleSetter(generator, exceptionTypes, fieldName, parameterType);
                                generateCollectionAdder(generator, exceptionTypes, fieldName, parameterType);
                                generateCollectionBuilder(generator, methodOwnerType, exceptionTypes, fieldName,
                                    parameterType, qualifiedParameterTypeSignature);
                                generateFieldBuilder(generator, methodOwnerType, exceptionTypes, fieldName,
                                    parameterType, qualifiedParameterTypeSignature);
                            } catch (JavaModelException e) {
                            }
                        }
                    }
                });

            generator.endBuilderBaseClass();
            typesToGenerateInnerBuilders.remove(typeSignature);
            typesAlradyGeneratedInnerBuilders.add(typeSignature);
        }
    }

    private void generateSimpleSetter(BuilderSourceGenerator generator, String[] exceptionTypes, String fieldName,
        String parameterType) {
        generator.addFieldSetter(fieldName, parameterType, exceptionTypes);
    }

    private void generateCollectionAdder(BuilderSourceGenerator generator, String[] exceptionTypes,
        String fieldName, String elementType) {
        boolean isCollection = elementType.startsWith("java.util.Collection<");
        boolean isList = elementType.startsWith("java.util.List<");
        boolean isArrayList = elementType.startsWith("java.util.ArrayList<");
        boolean isLinkedList = elementType.startsWith("java.util.LinkedList<");
        boolean isSet = elementType.startsWith("java.util.Set<");
        boolean isHashSet = elementType.startsWith("java.util.HashSet<");
        boolean isTreeSet = elementType.startsWith("java.util.TreeSet<");

        if (!elementType.endsWith("<?>")
                && (isCollection || isList || isArrayList || isLinkedList || isSet || isHashSet || isTreeSet)) {
            String elementName;

            elementName = pluralToSingle(fieldName);

            String concreteListType = "java.util.ArrayList";

            if (isLinkedList) {
                concreteListType = "java.util.LinkedList";
            } else if (isTreeSet) {
                concreteListType = "java.util.TreeSet";
            } else if (isSet || isHashSet) {
                concreteListType = "java.util.HashSet";
            }

            generator.addCollectionElementSetter(fieldName, elementType, elementName, concreteListType,
                exceptionTypes);
        }
    }

    private void generateCollectionBuilder(BuilderSourceGenerator generator, IType enclosingType,
        String[] exceptionTypes, String fieldName, String fieldType, String fieldTypeSignature) throws Exception {
        boolean isCollection = fieldType.startsWith("java.util.Collection<");
        boolean isList = fieldType.startsWith("java.util.List<");
        boolean isArrayList = fieldType.startsWith("java.util.ArrayList<");
        boolean isLinkedList = fieldType.startsWith("java.util.LinkedList<");
        boolean isSet = fieldType.startsWith("java.util.Set<");
        boolean isHashSet = fieldType.startsWith("java.util.HashSet<");
        boolean isTreeSet = fieldType.startsWith("java.util.TreeSet<");

        if (!fieldType.endsWith("<?>")
                && (isCollection || isList || isArrayList || isLinkedList || isSet || isHashSet || isTreeSet)) {
            String elementName;

            elementName = pluralToSingle(fieldName);

            String concreteListType = "java.util.ArrayList";

            if (isLinkedList) {
                concreteListType = "java.util.LinkedList";
            } else if (isTreeSet) {
                concreteListType = "java.util.TreeSet";
            } else if (isSet || isHashSet) {
                concreteListType = "java.util.HashSet";
            }

            String collectionQName = SignatureResolver.resolveSignature(enclosingType, fieldTypeSignature);
            String elementTypeSignature = getElementTypeSignature(collectionQName);
            IType resolveElementType = SignatureResolver.resolveType(enclosingType, elementTypeSignature);

            if (resolveElementType != null && resolveElementType.isClass() && resolveElementType.isStructureKnown()
                    && !resolveElementType.isBinary()) {
                generator.addCollectionElementBuilder(fieldName, fieldType, elementName, concreteListType,
                    exceptionTypes);
                addTypeToGenerateInnerBuilder(Signature.createTypeSignature(resolveElementType.getFullyQualifiedName(),
                        true));
            }
        }
    }

    private String getElementTypeSignature(String resolvedFieldTypeSignature) {
        String fieldTypeArgumentSignature = Signature.getTypeArguments(resolvedFieldTypeSignature)[0];
        return fieldTypeArgumentSignature.startsWith("+") || fieldTypeArgumentSignature.startsWith("-") ? fieldTypeArgumentSignature
                .substring(1)
                : fieldTypeArgumentSignature.equals("*") ? "Qjava.lang.Object;" : fieldTypeArgumentSignature;
    }

    private void generateFieldBuilder(BuilderSourceGenerator generator, IType enclosingType,
        String[] exceptionTypes, String fieldName, String parameterType, String parameterTypeSignature)
        throws Exception {
        IType resolveType = SignatureResolver.resolveType(enclosingType, parameterTypeSignature);

        if (resolveType != null && resolveType.isClass() && resolveType.isStructureKnown() && !resolveType.isBinary()) {
            generator.addFieldBuilder(fieldName, parameterType, exceptionTypes);
            addTypeToGenerateInnerBuilder(Signature.createTypeSignature(resolveType.getFullyQualifiedName(), true));
        }
    }

    private String fieldnameFromSetter(IMethod method) {
        return fieldNameFromSetterName(method.getElementName());
    }

    private String fieldNameFromSetterName(String setterName) {
        String fieldName = setterName.substring(SETTER_PREFIX.length());

        return Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private String pluralToSingle(String name) {
        String elementName;

        if (name.endsWith("Houses")) {
            elementName = name.substring(0, name.length() - 1);
        } else if (name.endsWith("ses")) {
            elementName = name.substring(0, name.length() - 2);
        } else if (name.endsWith("ies")) {
            elementName = name.substring(0, name.length() - 3) + "y";
        } else if (name.endsWith("ves")) {
            elementName = name.substring(0, name.length() - 3) + "f";
        } else if (name.endsWith("ees")) {
            elementName = name.substring(0, name.length() - 1);
        } else if (name.endsWith("es")) {
            elementName = name.substring(0, name.length() - 2);
        } else if (name.endsWith("s")) {
            elementName = name.substring(0, name.length() - 1);
        } else {
            elementName = name + "Element";
        }

        return elementName;
    }
}
