package com.ttp.and_jacoco.task

import com.ttp.and_jacoco.extension.JacocoExtension
import com.ttp.and_jacoco.report.ReportGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.jacoco.core.diff.CodeDiff

class BranchDiffTask extends DefaultTask {
    def currentName//当前分支名
    JacocoExtension jacocoExtension

    @TaskAction
    def getDiffClass() {
        println("downloadEcData start")
        downloadEcData()
        println("downloadEcData end")

        readDiffMethodList()

        if (jacocoExtension.reportDirectory == null) {
            jacocoExtension.reportDirectory = "${project.buildDir.getAbsolutePath()}/outputs/report"
        }
        ReportGenerator generator = new ReportGenerator(jacocoExtension.execDir, toFileList(jacocoExtension.classDirectories),
                toFileList(jacocoExtension.sourceDirectories), new File(jacocoExtension.reportDirectory))
        println("report: " + jacocoExtension.execDir + ", " + toFileList(jacocoExtension.classDirectories).toString()
                + ", " + toFileList(jacocoExtension.sourceDirectories).toString() + ", " + jacocoExtension.reportDirectory)
        generator.create()
    }

    def toFileList(List<String> path) {
        List<File> list = new ArrayList<>(path.size())
        for (String s : path)
            list.add(new File(s))
        return list
    }

    def readDiffMethodList() {
        File file = new File("${project.buildDir.getAbsolutePath()}/outputs/diff/diffMethodFile.txt")
        List<String> diffMethodList = new ArrayList<>()
        List<String> diffClassList = new ArrayList<>()
        try {
            BufferedReader fis = new BufferedReader(new FileReader(file))
            String line
            while ((line = fis.readLine()) != null) {
                if (line.contains("class:")) {
                    diffClassList.add(line.substring(6))
                } else {
                    diffMethodList.add(line)
                }
            }
            fis.close()
        } catch (Exception e) {
            e.printStackTrace();
        }
        CodeDiff.getInstance().setDiffMethodList(diffMethodList)
        CodeDiff.getInstance().setDiffClassList(diffClassList)
    }

    void readFiles(String dirPath, Closure closure) {
        File file = new File(dirPath);
        if (!file.exists()) {
            return
        }
        File[] files = file.listFiles();
        for (File classFile : files) {
            if (classFile.isDirectory()) {
                readFiles(classFile.getAbsolutePath(), closure);
            } else {
                if (classFile.getName().endsWith(".class")) {
                    if (!closure.call(classFile)) {
                        classFile.delete()
                    }
                } else {
                    classFile.delete()
                }
            }
        }
    }

    //下载ec数据文件
    def downloadEcData() {
        if (jacocoExtension.execDir == null) {
            jacocoExtension.execDir = "${project.buildDir}/jacoco/code-coverage/"
        }
        def dataDir = jacocoExtension.execDir
        new File(dataDir).mkdirs()

        def host = jacocoExtension.host
        def android = project.extensions.android
        def appName = android.defaultConfig.applicationId.replace(".","")
        def versionCode = android.defaultConfig.versionCode
//        http://10.10.17.105:8080/WebServer/JacocoApi/queryEcFile?appName=dealer&versionCode=100

        def curl = "curl ${host}/WebServer/JacocoApi/queryEcFile?appName=${appName}&versionCode=${versionCode}"
        println "curl = ${curl}"
        def text = curl.execute().text
        println "queryEcFile = ${text}"
        text = text.substring(text.indexOf("[") + 1, text.lastIndexOf("]")).replace("]", "")

        println "paths=${text}"

        if ("".equals(text)) {
            return
        }
        String[] paths = text.split(',')
        println "下载executionData 文件 length=${paths.length}"

        if (paths != null && paths.size() > 0) {
            for (String path : paths) {
                path = path.replace("\"", '')
                def name = path.substring(path.lastIndexOf("/") + 1)
                println "${path}"
                def file = new File(dataDir, name)
                if (file.exists() && file.length() > 0) //存在
                    continue
                println "downloadFile ${host}${path}"
                println "execute curl -o ${file.getAbsolutePath()} ${host}${path}"

                "curl -o ${file.getAbsolutePath()} ${host}${path}".execute().text
            }
        }
        println "downloadData 下载完成"

    }

    boolean deleteEmptyDir(File dir) {
        if (dir.isDirectory()) {
            boolean flag = true
            for (File f : dir.listFiles()) {
                if (deleteEmptyDir(f))
                    f.delete()
                else
                    flag = false
            }
            return flag
        }
        return false
    }
}