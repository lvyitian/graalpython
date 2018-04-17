/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.nodes.generator;

import java.util.List;

import com.oracle.graal.python.nodes.PNode;
import com.oracle.graal.python.nodes.control.BaseBlockNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

public final class GeneratorBlockNode extends BaseBlockNode implements GeneratorControlNode {

    private final int indexSlot;

    public GeneratorBlockNode(PNode[] statements, int indexSlot) {
        super(statements);
        this.indexSlot = indexSlot;
    }

    public static GeneratorBlockNode create(PNode[] statements, int indexSlot) {
        return new GeneratorBlockNode(statements, indexSlot);
    }

    public int getIndexSlot() {
        return indexSlot;
    }

    @Override
    public GeneratorBlockNode insertNodesBefore(PNode insertBefore, List<PNode> insertees) {
        return new GeneratorBlockNode(insertStatementsBefore(insertBefore, insertees), getIndexSlot());
    }

    @ExplodeLoop
    @Override
    public Object execute(VirtualFrame frame) {
        Object result = null;

        for (int i = 0; i < statements.length; i++) {
            final int currentIndex = getIndex(frame, indexSlot);

            if (i < currentIndex) {
                continue;
            }

            result = statements[i].execute(frame);
            setIndex(frame, indexSlot, currentIndex + 1);
        }

        reset(frame);
        return result;
    }

    public void reset(VirtualFrame frame) {
        setIndex(frame, indexSlot, 0);
    }
}
