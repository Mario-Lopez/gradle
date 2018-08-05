/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp.tooling.r410

import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.tooling.model.cpp.CppApplication
import org.gradle.tooling.model.cpp.CppExecutable
import org.gradle.tooling.model.cpp.CppLibrary
import org.gradle.tooling.model.cpp.CppProject
import org.gradle.tooling.model.cpp.CppSharedLibrary
import org.gradle.tooling.model.cpp.CppStaticLibrary
import org.gradle.tooling.model.cpp.CppTestSuite

@ToolingApiVersion(">=4.10")
@TargetGradleVersion(">=4.10")
class CppModelCrossVersionSpec extends ToolingApiSpecification {
    def "has empty model when root project does not apply any C++ plugins"() {
        buildFile << """
            apply plugin: 'java-library'
        """

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.projectIdentifier.projectPath == ':'
        project.projectIdentifier.buildIdentifier.rootDir == projectDir
        project.mainComponent == null
        project.testComponent == null
    }

    def "can query model when root project applies C++ application plugin"() {
        settingsFile << """
            rootProject.name = 'app'
        """
        buildFile << """
            apply plugin: 'cpp-application'
        """
        def headerDir = file('src/main/headers')
        def src1 = file('src/main/cpp/app.cpp').createFile()
        def src2 = file('src/main/cpp/app-impl.cpp').createFile()

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.projectIdentifier.projectPath == ':'
        project.projectIdentifier.buildIdentifier.rootDir == projectDir

        project.mainComponent instanceof CppApplication
        project.mainComponent.baseName == 'app'

        project.mainComponent.binaries.size() == 2

        def debugBinary = project.mainComponent.binaries[0]
        debugBinary instanceof CppExecutable
        debugBinary.name == 'mainDebug'
        debugBinary.baseName == 'app'
        debugBinary.compilationDetails.sources as Set == [src1, src2] as Set
        debugBinary.compilationDetails.frameworkSearchPaths.empty
        !debugBinary.compilationDetails.systemHeaderSearchPaths.empty
        debugBinary.compilationDetails.userHeaderSearchPaths == [headerDir]
        debugBinary.compilationDetails.macroDefines.empty
        debugBinary.compilationDetails.macroUndefines.empty
        debugBinary.compilationDetails.additionalArgs.empty
        debugBinary.compilationDetails.compileTask.path == ":compileDebugCpp"
        debugBinary.compilationDetails.compileTask.name == "compileDebugCpp"
        debugBinary.linkageDetails.outputLocation == file("build/exe/main/debug/app")
        debugBinary.linkageDetails.additionalArgs.empty
        debugBinary.linkageDetails.linkTask.path == ":linkDebug"
        debugBinary.linkageDetails.linkTask.name == "linkDebug"

        def releaseBinary = project.mainComponent.binaries[1]
        releaseBinary instanceof CppExecutable
        releaseBinary.name == 'mainRelease'
        releaseBinary.baseName == 'app'
        releaseBinary.compilationDetails.sources as Set == [src1, src2] as Set
        releaseBinary.compilationDetails.frameworkSearchPaths.empty
        !releaseBinary.compilationDetails.systemHeaderSearchPaths.empty
        releaseBinary.compilationDetails.userHeaderSearchPaths == [headerDir]
        releaseBinary.compilationDetails.macroDefines.empty
        releaseBinary.compilationDetails.macroUndefines.empty
        releaseBinary.compilationDetails.additionalArgs.empty
        releaseBinary.compilationDetails.compileTask.path == ":compileReleaseCpp"
        releaseBinary.compilationDetails.compileTask.name == "compileReleaseCpp"
        releaseBinary.linkageDetails.outputLocation == file("build/exe/main/release/stripped/app")
        releaseBinary.linkageDetails.additionalArgs.empty
        releaseBinary.linkageDetails.linkTask.path == ":stripSymbolsRelease"
        releaseBinary.linkageDetails.linkTask.name == "stripSymbolsRelease"

        project.testComponent == null
    }

    def "can query model when root project applies C++ library plugin"() {
        settingsFile << """
            rootProject.name = 'lib'
        """
        buildFile << """
            apply plugin: 'cpp-library'
        """
        def headerDir = file('src/main/headers')
        def apiHeaderDir = file('src/main/public')
        def src1 = file('src/main/cpp/lib.cpp').createFile()
        def src2 = file('src/main/cpp/lib-impl.cpp').createFile()

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.mainComponent instanceof CppLibrary
        project.mainComponent.baseName == 'lib'

        project.mainComponent.binaries.size() == 2
        def debugBinary = project.mainComponent.binaries[0]
        debugBinary instanceof CppSharedLibrary
        debugBinary.name == 'mainDebug'
        debugBinary.baseName == 'lib'
        debugBinary.compilationDetails.sources as Set == [src1, src2] as Set
        debugBinary.compilationDetails.frameworkSearchPaths.empty
        !debugBinary.compilationDetails.systemHeaderSearchPaths.empty
        debugBinary.compilationDetails.userHeaderSearchPaths == [apiHeaderDir, headerDir]
        debugBinary.compilationDetails.macroDefines.empty
        debugBinary.compilationDetails.macroUndefines.empty
        debugBinary.compilationDetails.additionalArgs.empty
        debugBinary.compilationDetails.compileTask.path == ":compileDebugCpp"
        debugBinary.compilationDetails.compileTask.name == "compileDebugCpp"
        debugBinary.linkageDetails.outputLocation == file("build/lib/main/debug/liblib.dylib")
        debugBinary.linkageDetails.additionalArgs.empty
        debugBinary.linkageDetails.linkTask.path == ":linkDebug"
        debugBinary.linkageDetails.linkTask.name == "linkDebug"

        def releaseBinary = project.mainComponent.binaries[1]
        releaseBinary instanceof CppSharedLibrary
        releaseBinary.name == 'mainRelease'
        releaseBinary.baseName == 'lib'
        releaseBinary.compilationDetails.sources as Set == [src1, src2] as Set
        releaseBinary.compilationDetails.frameworkSearchPaths.empty
        !releaseBinary.compilationDetails.systemHeaderSearchPaths.empty
        releaseBinary.compilationDetails.userHeaderSearchPaths == [apiHeaderDir, headerDir]
        releaseBinary.compilationDetails.macroDefines.empty
        releaseBinary.compilationDetails.macroUndefines.empty
        releaseBinary.compilationDetails.additionalArgs.empty
        releaseBinary.compilationDetails.compileTask.path == ":compileReleaseCpp"
        releaseBinary.compilationDetails.compileTask.name == "compileReleaseCpp"
        releaseBinary.linkageDetails.outputLocation == file("build/lib/main/release/stripped/liblib.dylib")
        releaseBinary.linkageDetails.additionalArgs.empty
        releaseBinary.linkageDetails.linkTask.path == ":stripSymbolsRelease"
        releaseBinary.linkageDetails.linkTask.name == "stripSymbolsRelease"

        project.testComponent == null
    }

    def "can query model when root project applies C++ unit test plugin"() {
        settingsFile << """
            rootProject.name = 'tests'
        """
        buildFile << """
            apply plugin: 'cpp-unit-test'
        """
        def headerDir = file('src/test/headers')
        def src1 = file('src/test/cpp/test-main.cpp').createFile()
        def src2 = file('src/test/cpp/test2.cpp').createFile()

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.mainComponent == null
        project.testComponent instanceof CppTestSuite
        project.testComponent.baseName == 'testsTest'

        project.testComponent.binaries.size() == 1
        def testBinary = project.testComponent.binaries[0]
        testBinary instanceof CppExecutable
        testBinary.name == 'testExecutable'
        testBinary.baseName == 'testsTest'
        testBinary.compilationDetails.sources as Set == [src1, src2] as Set
        testBinary.compilationDetails.frameworkSearchPaths.empty
        !testBinary.compilationDetails.systemHeaderSearchPaths.empty
        testBinary.compilationDetails.userHeaderSearchPaths == [headerDir]
        testBinary.compilationDetails.macroDefines.empty
        testBinary.compilationDetails.macroUndefines.empty
        testBinary.compilationDetails.additionalArgs.empty
        testBinary.compilationDetails.compileTask.path == ":compileTestCpp"
        testBinary.compilationDetails.compileTask.name == "compileTestCpp"
        testBinary.linkageDetails.linkTask.path == ":linkTest"
        testBinary.linkageDetails.linkTask.name == "linkTest"
    }

    def "can query model when root project applies C++ application and unit test plugins"() {
        settingsFile << """
            rootProject.name = 'app'
        """
        buildFile << """
            apply plugin: 'cpp-application'
            apply plugin: 'cpp-unit-test'
        """

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.mainComponent instanceof CppApplication
        project.mainComponent.baseName == 'app'
        project.testComponent instanceof CppTestSuite
        project.testComponent.baseName == 'appTest'
        project.testComponent.binaries[0].compilationDetails.userHeaderSearchPaths == [file('src/test/headers'), file('src/main/headers')]
    }

    def "can query model when root project applies C++ library and unit test plugins"() {
        buildFile << """
            apply plugin: 'cpp-library'
            apply plugin: 'cpp-unit-test'
        """

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.mainComponent instanceof CppLibrary
        project.testComponent instanceof CppTestSuite
        project.testComponent.binaries[0].compilationDetails.userHeaderSearchPaths == [file('src/test/headers'), file('src/main/public'), file('src/main/headers')]
    }

    def "can query model for customized C++ application"() {
        settingsFile << """
            rootProject.name = 'app'
        """
        buildFile << """
            apply plugin: 'cpp-application'
            application {
                baseName = 'some-app'
                source.from 'src'
                privateHeaders.from = ['include']
                binaries.configureEach {
                    compileTask.get().compilerArgs.add("--compile=\$name")
                    compileTask.get().macros = [VARIANT: name]
                    linkTask.get().linkerArgs.add("--link=\$name")
                } 
            }
        """
        def headerDir = file('include')
        def src1 = file('src/main/cpp/app.cpp').createFile()
        def src2 = file('src/app-impl.cpp').createFile()

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.mainComponent instanceof CppApplication
        project.mainComponent.baseName == 'some-app'
        project.mainComponent.binaries.size() == 2

        def debugBinary = project.mainComponent.binaries[0]
        debugBinary instanceof CppExecutable
        debugBinary.name == 'mainDebug'
        debugBinary.baseName == 'some-app'
        debugBinary.compilationDetails.sources as Set == [src1, src2] as Set
        debugBinary.compilationDetails.userHeaderSearchPaths == [headerDir]
        debugBinary.compilationDetails.macroDefines.name == ['VARIANT']
        debugBinary.compilationDetails.macroDefines.value == ['mainDebug']
        debugBinary.compilationDetails.macroUndefines.empty
        debugBinary.compilationDetails.additionalArgs == ['--compile=mainDebug']
        debugBinary.linkageDetails.outputLocation == file("build/exe/main/debug/some-app")
        debugBinary.linkageDetails.additionalArgs == ['--link=mainDebug']

        def releaseBinary = project.mainComponent.binaries[1]
        releaseBinary instanceof CppExecutable
        releaseBinary.name == 'mainRelease'
        releaseBinary.baseName == 'some-app'
        releaseBinary.compilationDetails.sources as Set == [src1, src2] as Set
        releaseBinary.compilationDetails.userHeaderSearchPaths == [headerDir]
        releaseBinary.compilationDetails.macroDefines.name == ['VARIANT']
        releaseBinary.compilationDetails.macroDefines.value == ['mainRelease']
        releaseBinary.compilationDetails.macroUndefines.empty
        releaseBinary.compilationDetails.additionalArgs == ['--compile=mainRelease']
        releaseBinary.linkageDetails.outputLocation == file("build/exe/main/release/stripped/some-app")
        releaseBinary.linkageDetails.additionalArgs == ['--link=mainRelease']
    }

    def "can query model for customized C++ library"() {
        settingsFile << """
            rootProject.name = 'lib'
        """
        buildFile << """
            apply plugin: 'cpp-library'
            library {
                baseName = 'some-lib'
                linkage = [Linkage.STATIC, Linkage.SHARED]
                privateHeaders.from = []
                publicHeaders.from = ['include']
                binaries.configureEach(CppSharedLibrary) {
                    linkTask.get().linkerArgs.add("--link=\$name")
                }
                binaries.configureEach(CppStaticLibrary) {
                    createTask.get().staticLibArgs.add("--link=\$name")
                }
            }
        """
        def publicHeaders = file('include')

        when:
        def project = withConnection { connection -> connection.getModel(CppProject.class) }

        then:
        project.mainComponent instanceof CppLibrary
        project.mainComponent.baseName == 'some-lib'

        project.mainComponent.binaries.size() == 4

        def debugStaticBinary = project.mainComponent.binaries[0]
        debugStaticBinary instanceof CppStaticLibrary
        debugStaticBinary.name == 'mainDebugStatic'
        debugStaticBinary.baseName == 'some-lib'
        debugStaticBinary.compilationDetails.userHeaderSearchPaths == [publicHeaders]
        debugStaticBinary.compilationDetails.compileTask.path == ":compileDebugStaticCpp"
        debugStaticBinary.linkageDetails.outputLocation == file("build/lib/main/debug/static/libsome-lib.a")
        debugStaticBinary.linkageDetails.additionalArgs.empty
        debugStaticBinary.linkageDetails.linkTask.path == ":createDebugStatic"
        def debugSharedBinary = project.mainComponent.binaries[1]
        debugSharedBinary instanceof CppSharedLibrary
        debugSharedBinary.name == 'mainDebugShared'
        debugSharedBinary.baseName == 'some-lib'
        debugSharedBinary.compilationDetails.userHeaderSearchPaths == [publicHeaders]
        debugSharedBinary.compilationDetails.compileTask.path == ":compileDebugSharedCpp"
        debugSharedBinary.linkageDetails.outputLocation == file("build/lib/main/debug/shared/libsome-lib.dylib")
        debugSharedBinary.linkageDetails.additionalArgs == ["--link=mainDebugShared"]
        debugSharedBinary.linkageDetails.linkTask.path == ":linkDebugShared"
        def releaseStaticBinary = project.mainComponent.binaries[2]
        releaseStaticBinary instanceof CppStaticLibrary
        releaseStaticBinary.name == 'mainReleaseStatic'
        releaseStaticBinary.baseName == 'some-lib'
        releaseStaticBinary.compilationDetails.userHeaderSearchPaths == [publicHeaders]
        releaseStaticBinary.compilationDetails.compileTask.path == ":compileReleaseStaticCpp"
        releaseStaticBinary.linkageDetails.outputLocation == file("build/lib/main/release/static/libsome-lib.a")
        releaseStaticBinary.linkageDetails.additionalArgs.empty
        releaseStaticBinary.linkageDetails.linkTask.path == ":createReleaseStatic"
        def releaseSharedBinary = project.mainComponent.binaries[3]
        releaseSharedBinary instanceof CppSharedLibrary
        releaseSharedBinary.name == 'mainReleaseShared'
        releaseSharedBinary.baseName == 'some-lib'
        releaseSharedBinary.compilationDetails.userHeaderSearchPaths == [publicHeaders]
        releaseSharedBinary.compilationDetails.compileTask.path == ":compileReleaseSharedCpp"
        releaseSharedBinary.linkageDetails.outputLocation == file("build/lib/main/release/shared/stripped/libsome-lib.dylib")
        releaseSharedBinary.linkageDetails.additionalArgs == ["--link=mainReleaseShared"]
        releaseSharedBinary.linkageDetails.linkTask.path == ":stripSymbolsReleaseShared"
    }

    def "can query the models for each project in a build"() {
        settingsFile << """
            include 'app'
            include 'lib'
            include 'other'
        """
        buildFile << """
            project(':app') { 
                apply plugin: 'cpp-application'
                application {
                    dependencies { implementation project(':lib') }
                }
            }
            project(':lib') { 
                apply plugin: 'cpp-library' 
                apply plugin: 'cpp-unit-test' 
            }
        """

        when:
        def models = withConnection { connection -> connection.action(new FetchAllCppProjects()).run() }

        then:
        models.size() == 4

        def rootProject = models[0]
        rootProject.projectIdentifier.projectPath == ':'
        rootProject.mainComponent == null
        rootProject.testComponent == null

        def appProject = models[1]
        appProject.projectIdentifier.projectPath == ':app'
        appProject.mainComponent instanceof CppApplication
        appProject.mainComponent.binaries[0].compilationDetails.userHeaderSearchPaths == [file("app/src/main/headers"), file("lib/src/main/public")]
        appProject.mainComponent.binaries[0].compilationDetails.compileTask.projectIdentifier.projectPath == ':app'
        appProject.mainComponent.binaries[0].linkageDetails.linkTask.projectIdentifier.projectPath == ':app'
        appProject.testComponent == null

        def libProject = models[2]
        libProject.projectIdentifier.projectPath == ':lib'
        libProject.mainComponent instanceof CppLibrary
        libProject.mainComponent.binaries[0].compilationDetails.userHeaderSearchPaths == [file("lib/src/main/public"), file("lib/src/main/headers")]
        libProject.mainComponent.binaries[0].compilationDetails.compileTask.projectIdentifier.projectPath == ':lib'
        libProject.mainComponent.binaries[0].linkageDetails.linkTask.projectIdentifier.projectPath == ':lib'
        libProject.testComponent != null

        def otherProject = models[3]
        otherProject.projectIdentifier.projectPath == ':other'
        otherProject.mainComponent == null
        otherProject.testComponent == null
    }

    def "can query the models for each project in a composite build"() {
        settingsFile << """
            include 'app'
            includeBuild 'lib'
        """
        buildFile << """
            project(':app') { 
                apply plugin: 'cpp-application' 
            }
        """
        file("lib/build.gradle") << """
                apply plugin: 'cpp-library' 
                apply plugin: 'cpp-unit-test' 
        """

        when:
        def models = withConnection { connection -> connection.action(new FetchAllCppProjects()).run() }

        then:
        models.size() == 3

        def rootProject = models[0]
        rootProject.projectIdentifier.projectPath == ':'
        rootProject.projectIdentifier.buildIdentifier.rootDir == projectDir
        rootProject.mainComponent == null
        rootProject.testComponent == null

        def appProject = models[1]
        appProject.projectIdentifier.projectPath == ':app'
        appProject.projectIdentifier.buildIdentifier.rootDir == projectDir
        appProject.mainComponent instanceof CppApplication
        appProject.mainComponent.binaries[0].compilationDetails.compileTask.projectIdentifier.buildIdentifier.rootDir == projectDir
        appProject.mainComponent.binaries[0].compilationDetails.compileTask.projectIdentifier.projectPath == ':app'
        appProject.mainComponent.binaries[0].linkageDetails.linkTask.projectIdentifier.buildIdentifier.rootDir == projectDir
        appProject.mainComponent.binaries[0].linkageDetails.linkTask.projectIdentifier.projectPath == ':app'
        appProject.testComponent == null

        def libProject = models[2]
        libProject.projectIdentifier.projectPath == ':'
        libProject.projectIdentifier.buildIdentifier.rootDir == file('lib')
        libProject.mainComponent instanceof CppLibrary
        libProject.mainComponent.binaries[0].compilationDetails.compileTask.projectIdentifier.buildIdentifier.rootDir == file('lib')
        libProject.mainComponent.binaries[0].compilationDetails.compileTask.projectIdentifier.projectPath == ':'
        libProject.mainComponent.binaries[0].linkageDetails.linkTask.projectIdentifier.buildIdentifier.rootDir == file('lib')
        libProject.mainComponent.binaries[0].linkageDetails.linkTask.projectIdentifier.projectPath == ':'
        libProject.testComponent != null
    }
}
