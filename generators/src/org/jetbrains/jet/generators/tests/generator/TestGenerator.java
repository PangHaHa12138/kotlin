/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.generators.tests.generator;

import com.google.common.collect.Lists;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ContainerUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.JUnit3RunnerWithInners;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.utils.Printer;
import org.jetbrains.kotlin.generators.di.GeneratorsFileUtil;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

public class TestGenerator {

    public static enum TargetBackend {
        ANY,
        JVM,
        JS
    }

    public static final String NAVIGATION_METADATA = "navigationMetadata";

    private static final Set<String> GENERATED_FILES = ContainerUtil.newHashSet();
    private static final Class RUNNER = JUnit3RunnerWithInners.class;

    private final String suiteClassPackage;
    private final String suiteClassName;
    private final String baseTestClassName;
    private final Collection<TestClassModel> testClassModels;
    private final String testSourceFilePath;

    public TestGenerator(
            @NotNull String baseDir,
            @NotNull String suiteClassPackage,
            @NotNull String suiteClassName,
            @NotNull Class<? extends TestCase> baseTestClass,
            @NotNull Collection<? extends TestClassModel> testClassModels
    ) {
        this.suiteClassPackage = suiteClassPackage;
        this.suiteClassName = suiteClassName;
        this.baseTestClassName = baseTestClass.getSimpleName();
        this.testClassModels = Lists.newArrayList(testClassModels);

        this.testSourceFilePath = baseDir + "/" + this.suiteClassPackage.replace(".", "/") + "/" + this.suiteClassName + ".java";

        if (!GENERATED_FILES.add(testSourceFilePath)) {
            throw new IllegalArgumentException("Same test file already generated in current session: " + testSourceFilePath);
        }
    }

    public void generateAndSave() throws IOException {
        StringBuilder out = new StringBuilder();
        Printer p = new Printer(out);

        p.println(FileUtil.loadFile(new File("generators/injector-generator/copyright.txt")));
        p.println("package ", suiteClassPackage, ";");
        p.println();
        p.println("import com.intellij.testFramework.TestDataPath;");
        p.println("import ", RUNNER.getCanonicalName(), ";");
        p.println("import org.jetbrains.jet.JetTestUtils;");
        p.println("import org.jetbrains.jet.test.InnerTestClasses;");
        p.println("import org.jetbrains.jet.test.TestMetadata;");
        p.println("import org.junit.runner.RunWith;");
        p.println();
        p.println("import java.io.File;");
        p.println("import java.util.regex.Pattern;");
        p.println();
        p.println("/** This class is generated by {@link ", JetTestUtils.TEST_GENERATOR_NAME, "}. DO NOT MODIFY MANUALLY */");

        generateSuppressAllWarnings(p);
        if (testClassModels.size() == 1) {
            TestClassModel theOnlyTestClass = testClassModels.iterator().next();
            generateTestClass(p, new DelegatingTestClassModel(theOnlyTestClass) {
                @Override
                public String getName() {
                    return suiteClassName;
                }
            }, false);
        }
        else {
            generateTestClass(p, new TestClassModel() {
                @NotNull
                @Override
                public Collection<TestClassModel> getInnerTestClasses() {
                    return testClassModels;
                }

                @NotNull
                @Override
                public Collection<TestMethodModel> getTestMethods() {
                    return Collections.emptyList();
                }

                @Override
                public boolean isEmpty() {
                    return false;
                }

                @Override
                public String getName() {
                    return suiteClassName;
                }

                @Override
                public String getDataString() {
                    return null;
                }

                @Nullable
                @Override
                public String getDataPathRoot() {
                    return null;
                }
            }, false);
        }

        File testSourceFile = new File(testSourceFilePath);
        GeneratorsFileUtil.writeFileIfContentChanged(testSourceFile, out.toString(), false);
    }

    private void generateTestClass(Printer p, TestClassModel testClassModel, boolean isStatic) {
        String staticModifier = isStatic ? "static " : "";

        generateMetadata(p, testClassModel);
        generateTestDataPath(p, testClassModel);
        generateInnerClassesAnnotation(p, testClassModel);
        p.println("@RunWith(", RUNNER.getSimpleName(), ".class)");

        p.println("public " + staticModifier + "class ", testClassModel.getName(), " extends ", baseTestClassName, " {");
        p.pushIndent();

        Collection<TestMethodModel> testMethods = testClassModel.getTestMethods();
        Collection<TestClassModel> innerTestClasses = testClassModel.getInnerTestClasses();

        for (Iterator<TestMethodModel> iterator = testMethods.iterator(); iterator.hasNext(); ) {
            TestMethodModel testMethodModel = iterator.next();
            generateTestMethod(p, testMethodModel);
            if (iterator.hasNext() || !innerTestClasses.isEmpty()) {
                p.println();
            }
        }

        for (Iterator<TestClassModel> iterator = innerTestClasses.iterator(); iterator.hasNext(); ) {
            TestClassModel innerTestClass = iterator.next();
            if (!innerTestClass.isEmpty()) {
                generateTestClass(p, innerTestClass, true);
                if (iterator.hasNext()) {
                    p.println();
                }
            }
        }

        p.popIndent();
        p.println("}");
    }

    private static void generateTestMethod(Printer p, TestMethodModel testMethodModel) {
        generateMetadata(p, testMethodModel);
        p.println("public void ", testMethodModel.getName(), "() throws Exception {");
        p.pushIndent();

        testMethodModel.generateBody(p);

        p.popIndent();
        p.println("}");
    }

    private static void generateMetadata(Printer p, TestEntityModel testDataSource) {
        String dataString = testDataSource.getDataString();
        if (dataString != null) {
            p.println("@TestMetadata(\"", dataString, "\")");
        }
    }

    private static void generateTestDataPath(Printer p, TestClassModel testClassModel) {
        String dataPathRoot = testClassModel.getDataPathRoot();
        if (dataPathRoot != null) {
            p.println("@TestDataPath(\"", dataPathRoot, "\")");
        }
    }

    private static void generateInnerClassesAnnotation(Printer p, TestClassModel testClassModel) {
        Collection<TestClassModel> innerTestClasses = testClassModel.getInnerTestClasses();
        if (innerTestClasses.isEmpty()) return;
        p.print("@InnerTestClasses({");

        boolean isFirst = true;
        for (TestClassModel innerTestClass : innerTestClasses) {
            if (!innerTestClass.isEmpty()) {
                if (!isFirst) {
                    p.printWithNoIndent(", ");
                }
                else {
                    isFirst = false;
                }

                p.printWithNoIndent(testClassModel.getName(), ".", innerTestClass.getName(), ".class");
            }
        }
        p.printlnWithNoIndent("})");
    }

    private static void generateSuppressAllWarnings(Printer p) {
        p.println("@SuppressWarnings(\"all\")");
    }
}
