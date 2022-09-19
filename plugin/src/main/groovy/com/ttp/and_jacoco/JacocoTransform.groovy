package com.ttp.and_jacoco

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import com.ttp.and_jacoco.extension.JacocoExtension
import groovy.io.FileType
import org.gradle.api.Project
import org.jacoco.core.diff.ClassInfo
import org.jacoco.core.diff.CodeDiff
import org.jacoco.core.diff.MethodInfo

class JacocoTransform extends Transform {
    Project project

    JacocoExtension jacocoExtension

    JacocoTransform(Project project, JacocoExtension jacocoExtension) {
        this.project = project
        this.jacocoExtension = jacocoExtension
    }

    @Override
    String getName() {
        return "jacoco"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return true
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        def dirInputs = new HashSet<>()
        def jarInputs = new HashSet<>()

        if (!transformInvocation.isIncremental()) {
            transformInvocation.getOutputProvider().deleteAll()
        }

        transformInvocation.inputs.each { input ->
            input.directoryInputs.each { dirInput ->
                dirInputs.add(dirInput)
            }
            input.jarInputs.each { jarInput ->
                jarInputs.add(jarInput)
            }
        }

        if (!dirInputs.isEmpty() || !jarInputs.isEmpty()) {
            if (jacocoExtension.jacocoEnable) {
                CodeDiff.getInstance().diffBranchToBranch("E:\\workspace\\AndJacoco", "jacoco_test_zhiqi", "jacoco_test_main_zhiqi")
                writeDiffToFile()
            }
            //对diff方法插入探针
            inject(transformInvocation, dirInputs, jarInputs, jacocoExtension.includes)
        }
    }

    def writeDiffToFile() {
        List<ClassInfo> classInfos = CodeDiff.getInstance().getClassInfos()
        String diffMethodPath = "${project.buildDir.getAbsolutePath()}/outputs/diff/diffMethodFile.txt"
        File diffMethodParent = new File(diffMethodPath).getParentFile()
        if (!diffMethodParent.exists()) diffMethodParent.mkdirs()
        FileOutputStream diffMethodFos = new FileOutputStream(diffMethodPath)

        for (ClassInfo classInfo : classInfos) {
            diffMethodFos.write(("class:" + classInfo.getPackages() + "." + classInfo.getClassName() + "\n").getBytes())
            for (MethodInfo methodInfo: classInfo.getMethodInfos()) {
                diffMethodFos.write((methodInfo.methodName + "\n").getBytes())
            }
        }
        diffMethodFos.close()
    }

    def inject(TransformInvocation transformInvocation, def dirInputs, def jarInputs, List<String> includes) {

        ClassInjector injector = new ClassInjector(includes)
        if (!dirInputs.isEmpty()) {
            dirInputs.each { dirInput ->
                File dirOutput = transformInvocation.outputProvider.getContentLocation(dirInput.getName(),
                        dirInput.getContentTypes(), dirInput.getScopes(),
                        Format.DIRECTORY)
                FileUtils.mkdirs(dirOutput)

                if (transformInvocation.incremental) {
                    dirInput.changedFiles.each { entry ->
                        File fileInput = entry.getKey()
                        File fileOutputTransForm = new File(fileInput.getAbsolutePath().replace(
                                dirInput.file.getAbsolutePath(), dirOutput.getAbsolutePath()))
                        FileUtils.mkdirs(fileOutputTransForm.parentFile)
                        Status fileStatus = entry.getValue()
                        switch (fileStatus) {
                            case Status.ADDED:
                            case Status.CHANGED:
                                if (fileInput.isDirectory()) {
                                    return // continue.
                                }
                                if (jacocoExtension.jacocoEnable && CodeDiff.getInstance().isContainsClass(getClassName(fileInput))) { //CoverageBuilder.classInfos.contains(getClassName(fileInput))\DiffAnalyzer.getInstance().containsClass(getClassName(fileInput))
                                    injector.doClass(fileInput, fileOutputTransForm)
                                } else {
                                    FileUtils.copyFile(fileInput, fileOutputTransForm)
                                }
                                break
                            case Status.REMOVED:
                                if (fileOutputTransForm.exists()) {
                                    if (fileOutputTransForm.isDirectory()) {
                                        fileOutputTransForm.deleteDir()
                                    } else {
                                        fileOutputTransForm.delete()
                                    }
                                    println("REMOVED output file Name:${fileOutputTransForm.name}")
                                }
                                break
                        }
                    }
                } else {
                    dirInput.file.traverse(type: FileType.FILES) { fileInput ->
                        File fileOutputTransForm = new File(fileInput.getAbsolutePath().replace(
                                dirInput.file.getAbsolutePath(), dirOutput.getAbsolutePath()))
                        FileUtils.mkdirs(fileOutputTransForm.parentFile)
                        if (jacocoExtension.jacocoEnable && CodeDiff.getInstance().isContainsClass(getClassName(fileInput))) { //CoverageBuilder.classInfos.contains(getClassName(fileInput))\DiffAnalyzer.getInstance().containsClass(getClassName(fileInput))
                            injector.doClass(fileInput, fileOutputTransForm)
                        } else {
                            FileUtils.copyFile(fileInput, fileOutputTransForm)
                        }
                    }
                }
            }
        }

        if (!jarInputs.isEmpty()) {
            jarInputs.each { jarInput ->
                File jarInputFile = jarInput.file
                File jarOutputFile = transformInvocation.outputProvider.getContentLocation(
                        jarInputFile.getName(), getOutputTypes(), getScopes(), Format.JAR
                )

                FileUtils.mkdirs(jarOutputFile.parentFile)

                switch (jarInput.status) {
                    case Status.NOTCHANGED:
                        if (transformInvocation.incremental) {
                            break
                        }
                    case Status.ADDED:
                    case Status.CHANGED:
                        if (jacocoExtension.jacocoEnable) {
                            injector.doJar(jarInputFile, jarOutputFile)
                        } else {
                            FileUtils.copyFile(jarInputFile, jarOutputFile)
                        }
                        break
                    case Status.REMOVED:
                        if (jarOutputFile.exists()) {
                            jarOutputFile.delete()
                        }
                        break
                }
            }
        }
    }

    def getClassName(File f) {
        return ClassProcessor.filePath2ClassName(f).replaceAll(".class", "")
    }
}