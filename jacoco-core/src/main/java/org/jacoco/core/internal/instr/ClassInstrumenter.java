/*******************************************************************************
 * Copyright (c) 2009, 2020 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.instr;

import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.diff.ClassInfo;
import org.jacoco.core.diff.CodeDiff;
import org.jacoco.core.diff.DiffAnalyzer;
import org.jacoco.core.diff.MethodInfo;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

/**
 * Adapter that instruments a class for coverage tracing.
 */
public class ClassInstrumenter extends ClassProbesVisitor {

    private final IProbeArrayStrategy probeArrayStrategy;

    private String className;

    /**
     * Emits a instrumented version of this class to the given class visitor.
     *
     * @param probeArrayStrategy this strategy will be used to access the probe array
     * @param cv                 next delegate in the visitor chain will receive the
     *                           instrumented class
     */
    public ClassInstrumenter(final IProbeArrayStrategy probeArrayStrategy,
                             final ClassVisitor cv) {
        super(cv);
        this.probeArrayStrategy = probeArrayStrategy;
    }

    @Override
    public void visit(final int version, final int access, final String name,
                      final String signature, final String superName,
                      final String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(final int access, final String name,
                                   final String desc, final String signature, final Object value) {
        InstrSupport.assertNotInstrumented(name, className);
        return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name,
                                     final String desc, final String signature,
                                     final String[] exceptions) {
        if (CodeDiff.getInstance().isContainsMethod(className, name)) {
            InstrSupport.assertNotInstrumented(name, this.className);
            MethodVisitor mv = this.cv.visitMethod(access, name, desc, signature, exceptions);
            if (mv == null) {
                return null;
            }
            MethodVisitor frameEliminator = new DuplicateFrameEliminator(mv);
            ProbeInserter probeVariableInserter = new ProbeInserter(access, name, desc, frameEliminator, this.probeArrayStrategy);
            return new MethodInstrumenter(probeVariableInserter, probeVariableInserter);
        } else {
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }

    @Override
    public void visitTotalProbeCount(final int count) {
        probeArrayStrategy.addMembers(cv, count);
    }

}
