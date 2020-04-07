package pt.up.fe.qsfl.instrumenter.passes;

import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import pt.up.fe.qsfl.common.model.Node;
import pt.up.fe.qsfl.common.model.Node.Type;
import pt.up.fe.qsfl.instrumenter.model.NodeRetriever;
import pt.up.fe.qsfl.instrumenter.runtime.Collector;
import pt.up.fe.qsfl.instrumenter.runtime.data.ValueProbeDispatcher;

import static pt.up.fe.qsfl.instrumenter.passes.PassUtils.allLocalVars;

public class ParameterDataPass implements Pass {

    private ValueProbeDispatcher dispatcher = new ValueProbeDispatcher();

    @Override
    public Outcome transform(CtClass c) throws Exception {

        for (CtBehavior b : c.getDeclaredBehaviors()) {
            try {
                handleBehavior(c, b);
            } catch (Exception e) {
            }
        }

        return Outcome.CONTINUE;
    }

    private void handleBehavior(CtClass c, CtBehavior b) throws Exception {
        if(InstrumentationPass.toSkip(c, b)) {
            return;
        }

        boolean skipMemberParameter =  b instanceof CtConstructor &&
                c.getDeclaringClass() != null &&
                !Modifier.isStatic(c.getModifiers());

        MethodInfo info = b.getMethodInfo();
        CodeAttribute ca = info.getCodeAttribute();

        Node n = NodeRetriever.getNode(c, b);
        Collector collector = Collector.instance();

        LocalVariableAttribute attr = (LocalVariableAttribute) ca.getAttribute(LocalVariableAttribute.tag);
        String[] localVarNames = allLocalVars(attr);
        CtClass[] params = b.getParameterTypes();
        int pos = (Modifier.isStatic(b.getModifiers()) || skipMemberParameter) ? 0 : 1;

        int i = skipMemberParameter ? 1 : 0;
        for (; i < params.length; i++) {
            String parameterName = "parameter#"+i;
            if (attr != null && attr.tableLength() > i + pos) {
                parameterName = localVarNames[i + pos];
            }
            Node parameterNode = collector.createNode(n, parameterName, Type.PARAMETER, n.getLine());

            String toInject = dispatcher.getInstrumentationString(params[i], parameterNode.getId(), "$args["+i+"]");
            if (toInject == null) {
                continue;
            }

            if (b instanceof CtConstructor) {
                ((CtConstructor) b).insertBeforeBody(toInject);
            } else {
                b.insertBefore(toInject);
            }
        }

        if (b instanceof CtMethod) {
            CtClass returnType = ((CtMethod) b).getReturnType();
            String toInject = dispatcher.getInstrumentationString(returnType, n.getId(), "$(w)$_");
            if (toInject != null) {
                b.insertAfter(toInject);
            }
        }
    }

}
