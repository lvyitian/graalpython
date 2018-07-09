/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.nodes.attributes;

import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.type.PythonClass;
import com.oracle.graal.python.nodes.PBaseNode;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.ConditionProfile;

public abstract class LookupAttributeInMRONode extends PBaseNode {

    public abstract static class Dynamic extends PBaseNode {

        public static LookupAttributeInMRONode.Dynamic create() {
            return LookupAttributeInMRONodeGen.DynamicNodeGen.create();
        }

        public abstract Object execute(Object klass, Object key);

        protected static boolean compareStrings(String key, String cachedKey) {
            return cachedKey.equals(key);
        }

        @Specialization(guards = "compareStrings(key, cachedKey)", limit = "2")
        @ExplodeLoop
        protected Object lookupConstantMRO(PythonClass klass, @SuppressWarnings("unused") String key,
                        @Cached("key") @SuppressWarnings("unused") String cachedKey,
                        @Cached("create(key)") LookupAttributeInMRONode lookup) {
            return lookup.execute(klass);
        }

        @Specialization
        protected Object lookup(PythonClass klass, Object key,
                        @Cached("create()") ReadAttributeFromObjectNode readAttrNode) {
            return LookupAttributeInMRONode.lookupSlow(klass, key, readAttrNode);
        }

        @SuppressWarnings("unused")
        @Fallback
        protected Object lookup(Object klass, Object key) {
            return PNone.NO_VALUE;
        }
    }

    private final String key;

    public LookupAttributeInMRONode(String key) {
        this.key = key;
    }

    public static LookupAttributeInMRONode create(String key) {
        return LookupAttributeInMRONodeGen.create(key);
    }

    /**
     * Looks up the {@code key} in the MRO of the {@code klass}.
     *
     * @return The lookup result, or {@link PNone#NO_VALUE} if the key isn't defined on any object
     *         in the MRO.
     */
    public abstract Object execute(PythonClass klass);

    final static class PythonClassAssumptionPair {
        public PythonClass cls;
        public Assumption assumption;

        public PythonClassAssumptionPair(PythonClass cls, Assumption assumption) {
            this.cls = cls;
            this.assumption = assumption;
        }
    }

    protected PythonClassAssumptionPair findAttrClassAndAssumptionInMRO(PythonClass klass) {
        PythonClass[] mro = klass.getMethodResolutionOrder();
        Assumption attrAssumption = null;
        for (int i = 0; i < mro.length; i++) {
            PythonClass kls = mro[i];
            if (attrAssumption == null) {
                attrAssumption = kls.createAttributeInMROFinalAssumption(key);
            } else {
                kls.setAttributesInMROFinalAssumption(key, attrAssumption);
            }

            if (kls.getStorage().containsKey(key)) {
                return new PythonClassAssumptionPair(kls, attrAssumption);
            }
        }
        return new PythonClassAssumptionPair(null, attrAssumption);
    }

    @Specialization(guards = {"klass == cachedKlass", "cachedAttrKlass != null"}, limit = "5", assumptions = {"lookupStable", "attributeInMROFinalAssumption"}, rewriteOn = IllegalStateException.class)
    protected Object lookupConstantMROCached(@SuppressWarnings("unused") PythonClass klass,
                    @Cached("klass") @SuppressWarnings("unused") PythonClass cachedKlass,
                    @Cached("cachedKlass.getLookupStableAssumption()") @SuppressWarnings("unused") Assumption lookupStable,
                    @Cached("create()") ReadAttributeFromObjectNode readAttrNode,
                    @Cached("findAttrClassAndAssumptionInMRO(cachedKlass)") @SuppressWarnings("unused") PythonClassAssumptionPair cachedPair,
                    @Cached("cachedPair.cls") PythonClass cachedAttrKlass,
                    @Cached("cachedPair.assumption") @SuppressWarnings("unused") Assumption attributeInMROFinalAssumption,
                    @Cached("createBinaryProfile()") ConditionProfile attributeDeletedProfile) {
        Object value = readAttrNode.execute(cachedAttrKlass, key);
        if (attributeDeletedProfile.profile(value == PNone.NO_VALUE)) {
            // in case the attribute was deleted
            throw new IllegalStateException();
        }
        return value;
    }

    @Specialization(guards = {"klass == cachedKlass", "mroLength < 32"}, limit = "5", assumptions = "lookupStable")
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected Object lookupConstantMRO(@SuppressWarnings("unused") PythonClass klass,
                    @Cached("klass") @SuppressWarnings("unused") PythonClass cachedKlass,
                    @Cached("cachedKlass.getLookupStableAssumption()") @SuppressWarnings("unused") Assumption lookupStable,
                    @Cached("create()") ReadAttributeFromObjectNode readAttrNode,
                    @Cached(value = "cachedKlass.getMethodResolutionOrder()", dimensions = 1) PythonClass[] mro,
                    @Cached("mro.length") @SuppressWarnings("unused") int mroLength) {
        for (int i = 0; i < mro.length; i++) {
            PythonClass kls = mro[i];
            Object value = readAttrNode.execute(kls, key);
            if (value != PNone.NO_VALUE) {
                return value;
            }
        }
        return PNone.NO_VALUE;
    }

    @Specialization
    protected Object lookup(PythonClass klass,
                    @Cached("create()") ReadAttributeFromObjectNode readAttrNode) {
        return lookupSlow(klass, key, readAttrNode);
    }

    protected static Object lookupSlow(PythonClass klass, Object key, ReadAttributeFromObjectNode readAttrNode) {
        PythonClass[] mro = klass.getMethodResolutionOrder();
        for (int i = 0; i < mro.length; i++) {
            PythonClass kls = mro[i];
            Object value = readAttrNode.execute(kls, key);
            if (value != PNone.NO_VALUE) {
                return value;
            }
        }
        return PNone.NO_VALUE;
    }
}
