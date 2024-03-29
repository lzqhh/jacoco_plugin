/*******************************************************************************
 * Copyright (c) 2009, 2019 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.diff;

import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.jacoco.core.analysis.CoverageBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 代码版本比较
 */
public class CodeDiff {
    private final static String REF_HEADS = "refs/heads/";

    private static CodeDiff instance;

    private List<ClassInfo> classInfos;
    List<String> diffMethodList = new ArrayList<>();
    private List<String> diffClassList = new ArrayList<>();

    public static CodeDiff getInstance() {
        if (instance == null) {
            synchronized (CodeDiff.class) {
                if (instance == null)
                    instance = new CodeDiff();
            }
        }
        return instance;
    }

    public boolean isContainsClass(String currentClassName) {
        boolean isDiffClass = false;
        for (ClassInfo classInfo: classInfos) {
            if (currentClassName.contains(classInfo.getClassName())) {
                isDiffClass = true;
            }
        }
        return isDiffClass;
    }

    public boolean isContainsMethod(String currentClassName, String currentMethod) {
        if (classInfos == null || classInfos.isEmpty()) {
            return true;
        }
        String newCurrentClassName = currentClassName.replaceAll("/",".");
        for (ClassInfo classInfo : classInfos) {
            String className = classInfo.getPackages() + "." + classInfo.getClassName();
            if (newCurrentClassName.equals(className)) {
                for (MethodInfo methodInfo: classInfo.getMethodInfos()) {
                    String methodName = methodInfo.getMethodName();
                    if (currentMethod.equals(methodName)) {
                        System.out.println("containsMethod: " + className + methodName);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 分支和分支之间的比较
     * @param gitPath           git路径
     * @param newBranchName     新分支名称
     * @param oldBranchName     旧分支名称
     * @return
     */
    public void diffBranchToBranch(String gitPath, String newBranchName, String oldBranchName) {
        classInfos = diffMethods(gitPath, newBranchName, oldBranchName);
    }

    public void setDiffMethodList(List<String> diffMethod) {
        diffMethodList = diffMethod;
    }

    public List<String> getDiffMethodList() {
        return diffMethodList;
    }

    public void setDiffClassList(List<String> diffClass) {
        diffClassList = diffClass;
    }

    public List<String> getDiffClassList() {
        return diffClassList;
    }

    public List<ClassInfo> getClassInfos() {
        return classInfos;
    }

    private List<ClassInfo> diffMethods(String gitPath, String newBranchName, String oldBranchName) {
        try {
            //  获取本地分支
            GitAdapter gitAdapter = new GitAdapter(gitPath);
            Git git = gitAdapter.getGit();
            Ref localBranchRef = gitAdapter.getRepository().exactRef(REF_HEADS + newBranchName);
            Ref localMasterRef = gitAdapter.getRepository().exactRef(REF_HEADS + oldBranchName);
            //  更新本地分支
            gitAdapter.checkOutAndPull(localMasterRef, oldBranchName);
            gitAdapter.checkOutAndPull(localBranchRef, newBranchName);
            //  获取分支信息
            AbstractTreeIterator newTreeParser = gitAdapter.prepareTreeParser(localBranchRef);
            AbstractTreeIterator oldTreeParser = gitAdapter.prepareTreeParser(localMasterRef);
            //  对比差异
            List<DiffEntry> diffs = git.diff().setOldTree(oldTreeParser).setNewTree(newTreeParser).setShowNameAndStatusOnly(true).call();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffFormatter df = new DiffFormatter(out);
            //设置比较器为忽略空白字符对比（Ignores all whitespace）
            df.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
            df.setRepository(git.getRepository());
            List<ClassInfo> allClassInfos = batchPrepareDiffMethod(gitAdapter, newBranchName, oldBranchName, df, diffs);
            return allClassInfos;
        }catch (Exception e) {
            e.printStackTrace();
        }
        return  new ArrayList<ClassInfo>();
    }

    /**
     * 多线程执行对比
     * @return
     */
    private List<ClassInfo> batchPrepareDiffMethod(final GitAdapter gitAdapter, final String branchName, final String oldBranchName, final DiffFormatter df, List<DiffEntry> diffs) {
        int threadSize = 100;
        int dataSize = diffs.size();
        int threadNum = dataSize / threadSize + 1;
        boolean special = dataSize % threadSize == 0;
        ExecutorService executorService = Executors.newFixedThreadPool(threadNum);

        List<Callable<List<ClassInfo>>> tasks = new ArrayList<Callable<List<ClassInfo>>>();
        Callable<List<ClassInfo>> task = null;
        List<DiffEntry> cutList = null;
        //  分解每条线程的数据
        for (int i = 0; i < threadNum; i++) {
            if (i == threadNum - 1) {
                if (special) {
                    break;
                }
                cutList = diffs.subList(threadSize * i, dataSize);
            } else {
                cutList = diffs.subList(threadSize * i, threadSize * (i + 1));
            }
            final List<DiffEntry> diffEntryList = cutList;
            task = new Callable<List<ClassInfo>>() {
                @Override
                public List<ClassInfo> call() throws Exception {
                    List<ClassInfo> allList = new ArrayList<ClassInfo>();
                    for (DiffEntry diffEntry : diffEntryList) {
                        ClassInfo classInfo = prepareDiffMethod(gitAdapter, branchName, oldBranchName, df, diffEntry);
                        if (classInfo != null) {
                            allList.add(classInfo);
                        }
                    }
                    return allList;
                }
            };
            // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
            tasks.add(task);
        }
        List<ClassInfo> allClassInfoList = new ArrayList<ClassInfo>();
        try {
            List<Future<List<ClassInfo>>> results = executorService.invokeAll(tasks);
            //结果汇总
            for (Future<List<ClassInfo>> future : results ) {
                allClassInfoList.addAll(future.get());
            }
        }catch (Exception e) {
            e.printStackTrace();
        }finally {
            // 关闭线程池
            executorService.shutdown();
        }
        return allClassInfoList;
    }

    /**
     * 单个差异文件对比
     * @param gitAdapter
     * @param branchName
     * @param oldBranchName
     * @param df
     * @param diffEntry
     * @return
     */
    private synchronized ClassInfo prepareDiffMethod(GitAdapter gitAdapter, String branchName, String oldBranchName, DiffFormatter df, DiffEntry diffEntry) {
        List<MethodInfo> methodInfoList = new ArrayList<MethodInfo>();
        try {
            String newJavaPath = diffEntry.getNewPath();
            //  排除测试类
            if (newJavaPath.contains("/src/test/java/")) {
                return null;
            }
            //  非java文件 和 删除类型不记录
            if ((!newJavaPath.endsWith(".java") && !newJavaPath.endsWith(".kt")) || diffEntry.getChangeType() == DiffEntry.ChangeType.DELETE){
                return null;
            }
            String newClassContent = gitAdapter.getBranchSpecificFileContent(branchName,newJavaPath);
            ASTGenerator newAstGenerator = new ASTGenerator(newClassContent);
            /*  新增类型   */
            if (diffEntry.getChangeType() == DiffEntry.ChangeType.ADD) {
                return newAstGenerator.getClassInfo();
            }
            /*  修改类型  */
            //  获取文件差异位置，从而统计差异的行数，如增加行数，减少行数
            FileHeader fileHeader = df.toFileHeader(diffEntry);
            List<int[]> addLines = new ArrayList<int[]>();
            List<int[]> delLines = new ArrayList<int[]>();
            EditList editList = fileHeader.toEditList();
            for(Edit edit : editList){
                if (edit.getLengthA() > 0) {
                    delLines.add(new int[]{edit.getBeginA(), edit.getEndA()});
                }
                if (edit.getLengthB() > 0 ) {
                    addLines.add(new int[]{edit.getBeginB(), edit.getEndB()});
                }
            }
            String oldJavaPath = diffEntry.getOldPath();
            String oldClassContent = gitAdapter.getBranchSpecificFileContent(oldBranchName,oldJavaPath);
            ASTGenerator oldAstGenerator = new ASTGenerator(oldClassContent);
            MethodDeclaration[] newMethods = newAstGenerator.getMethods();
            MethodDeclaration[] oldMethods = oldAstGenerator.getMethods();
            Map<String, MethodDeclaration> methodsMap = new HashMap<String, MethodDeclaration>();
            for (int i = 0; i < oldMethods.length; i++) {
                methodsMap.put(oldMethods[i].getName().toString()+ oldMethods[i].parameters().toString(), oldMethods[i]);
            }
            for (final MethodDeclaration method : newMethods) {
                // 如果方法名是新增的,则直接将方法加入List
                if (!ASTGenerator.isMethodExist(method, methodsMap)) {
                    MethodInfo methodInfo = newAstGenerator.getMethodInfo(method);
                    methodInfoList.add(methodInfo);
                    continue;
                }
                // 如果两个版本都有这个方法,则根据MD5判断方法是否一致
                if (!ASTGenerator.isMethodTheSame(method, methodsMap.get(method.getName().toString() + method.parameters().toString()))) {
                    MethodInfo methodInfo =  newAstGenerator.getMethodInfo(method);
                    methodInfoList.add(methodInfo);
                }
            }
            return newAstGenerator.getClassInfo(methodInfoList, addLines, delLines);
        }catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
