package pt.up.fe.qsfl.instrumenter.passes;

import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.Bytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import pt.up.fe.qsfl.common.model.Node;
import pt.up.fe.qsfl.instrumenter.granularity.Granularity;
import pt.up.fe.qsfl.instrumenter.granularity.GranularityFactory;
import pt.up.fe.qsfl.instrumenter.granularity.GranularityFactory.GranularityLevel;
import pt.up.fe.qsfl.instrumenter.runtime.Collector;
import pt.up.fe.qsfl.instrumenter.runtime.ProbeGroup;

public class InstrumentationPass implements Pass {

    private static final String HIT_VECTOR_TYPE = "[Z";
    public static final String HIT_VECTOR_NAME = "$__QSFL_HIT_VECTOR__";

    private final GranularityLevel granularity;

    public InstrumentationPass(GranularityLevel granularity) {
        this.granularity = granularity;
    }

    @Override
    public Outcome transform(CtClass c) throws Exception {

        // make field
        CtField f = CtField.make("private static boolean[] " + HIT_VECTOR_NAME + ";", c);
        f.setModifiers(f.getModifiers() | AccessFlag.SYNTHETIC);
        c.addField(f);

        // make class initializer
        CtConstructor initializer = c.makeClassInitializer();
        initializer.insertBefore(getVectorInitializer(c));

        for (CtBehavior b : c.getDeclaredBehaviors()) {
            handleBehavior(c, b);
        }

        return Outcome.CONTINUE;
    }

    public static String getVectorInitializer(CtClass c) {
        return HIT_VECTOR_NAME + " = " + Collector.class.getCanonicalName()
                + ".instance().getHitVector(\"" + c.getName() + "\");";
    }

    public static boolean toSkip(CtClass c, CtBehavior b) throws NotFoundException {
        MethodInfo info = b.getMethodInfo();
        CodeAttribute ca = info.getCodeAttribute();

        if (ca == null || (b.getModifiers() & AccessFlag.SYNTHETIC) != 0) {
            return true;
        }

        if (b instanceof CtConstructor) {
            return ((CtConstructor) b).isClassInitializer() || c.getEnclosingBehavior() != null || c.isEnum();

        }
        return false;
    }

    private void handleBehavior(CtClass c, CtBehavior b) throws Exception {
        if (toSkip(c, b)) {
            return;
        }

        MethodInfo info = b.getMethodInfo();
        CodeAttribute ca = info.getCodeAttribute();
        CodeIterator ci = ca.iterator();

        if (b instanceof CtConstructor) {
            ci.skipConstructor();
        }

        Granularity g = GranularityFactory.getGranularity(c, info, ci, granularity);

        for (int instrSize = 0, index, curLine; ci.hasNext();) {
            index = ci.next();

            curLine = info.getLineNumber(index);

            if (curLine == -1)
                continue;

            if (g.instrumentAtIndex(index, instrSize)) {
                Node n = g.getNode(c, b, curLine);
                Bytecode bc = getInstrumentationCode(c, n, info.getConstPool());
                ci.insert(index, bc.get());
                instrSize += bc.length();
            }

            if (g.stopInstrumenting())
                break;
        }
    }

    private Bytecode getInstrumentationCode(CtClass c, Node n, ConstPool constPool) {
        Bytecode b = new Bytecode(constPool);
        ProbeGroup.HitProbe p = getHitProbe(c, n);

        b.addGetstatic(c, HIT_VECTOR_NAME, HIT_VECTOR_TYPE);
        b.addIconst(p.getLocalId());
        b.addOpcode(Opcode.ICONST_1);
        b.addOpcode(Opcode.BASTORE);

        return b;
    }

    public ProbeGroup.HitProbe getHitProbe(CtClass cls, Node n) {
        Collector c = Collector.instance();

        return c.createHitProbe(cls.getName(), n.getId());
    }
}
