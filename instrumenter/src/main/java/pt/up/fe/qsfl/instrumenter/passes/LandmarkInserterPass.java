package pt.up.fe.qsfl.instrumenter.passes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import pt.up.fe.qsfl.annotations.LandmarkAnnotation;
import pt.up.fe.qsfl.annotations.LandmarkWith;
import pt.up.fe.qsfl.annotations.dispatchers.DefaultDispatcher;
import pt.up.fe.qsfl.annotations.dispatchers.Dispatcher;
import pt.up.fe.qsfl.annotations.handlers.LandmarkHandler;
import pt.up.fe.qsfl.common.model.Node;
import pt.up.fe.qsfl.common.model.Node.Type;
import pt.up.fe.qsfl.instrumenter.model.NodeRetriever;
import pt.up.fe.qsfl.instrumenter.runtime.Collector;
import pt.up.fe.qsfl.instrumenter.runtime.ProbeGroup.HitProbe;

import static pt.up.fe.qsfl.instrumenter.passes.PassUtils.allLocalVars;

public class LandmarkInserterPass implements Pass {

    public static final String LANDMARK_VECTOR_NAME = "$__QSFL_LANDMARK_VECTOR__";

    private Dispatcher dispatcher;
    private Dispatcher defaultDispatcher = new DefaultDispatcher();

    @Override
    public Outcome transform(CtClass c) throws Exception {
        try {
            //check if class contains instrumentation field
            c.getDeclaredField(InstrumentationPass.HIT_VECTOR_NAME);
        } catch (Exception e) {
            return Outcome.CANCEL;
        }

        dispatcher = getLandmarkDispatcher(c);
        if (dispatcher == null) {
            return Outcome.CONTINUE;
        }

        CtField f = CtField.make(
                "private static " + LandmarkHandler.class.getCanonicalName() + "[] " + LANDMARK_VECTOR_NAME + ";", c);
        f.setModifiers(f.getModifiers() | AccessFlag.SYNTHETIC);
        c.addField(f);

        CtConstructor initializer = c.makeClassInitializer();
        initializer.insertBefore(getVectorInitializer(c));

        List<LandmarkHandler> handlers = new ArrayList<LandmarkHandler>();
        for (CtBehavior b : c.getDeclaredBehaviors()) {
            try {
                handleBehavior(c, b, handlers);
            } catch (Exception e) {
            }
        }
        Collector.instance().addLandmarkVector(c.getName(), handlers);

        return Outcome.CONTINUE;
    }

    public static String getVectorInitializer(CtClass c) {
        return "{" + LANDMARK_VECTOR_NAME + " = " + Collector.class.getCanonicalName()
                + ".instance().getLandmarkVector(\"" + c.getName() + "\");}";
    }

    private Dispatcher getLandmarkDispatcher(CtClass c) {
        Object[] annotations = c.getAvailableAnnotations();

        for (Object o : annotations) {
            if (o instanceof LandmarkWith) {
                try {
                    LandmarkWith lw = (LandmarkWith) o;
                    return lw.value().newInstance();
                } catch (Exception e) {
                }
            }
        }

        return defaultDispatcher;
    }

    private void handleBehavior(CtClass c, CtBehavior b, List<LandmarkHandler> handlers) throws Exception {
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
        Object[][] paramsAnnotations = b.getAvailableParameterAnnotations();
        int pos = (Modifier.isStatic(b.getModifiers()) || skipMemberParameter) ? 0 : 1;

        int i = skipMemberParameter ? 1 : 0;
        for (; i < params.length; i++) {
            String parameterName = "parameter#"+i;
            if (attr != null && attr.tableLength() > i + pos) {
                parameterName = localVarNames[i + pos];
            }
            Node parameterNode = collector.createNode(n, parameterName, Type.PARAMETER, n.getLine());

            LandmarkHandler handler = getLandmarkHandler(params[i], paramsAnnotations[i], false);
            insertLandmark(c, b, parameterNode, handlers, "$args[" + i + "]", handler, false);
        }

        if (b instanceof CtMethod) {
            CtClass returnType = ((CtMethod) b).getReturnType();
            if (returnType != CtClass.voidType) {
                LandmarkHandler handler = getLandmarkHandler(returnType, b.getAvailableAnnotations(), true);
                insertLandmark(c, b, n, handlers, "($w)$_", handler, true);
            }
        }
    }

    private void insertLandmark(CtClass c, CtBehavior b, Node node, List<LandmarkHandler> handlers, String variable,
            LandmarkHandler handler, boolean isReturn) throws CannotCompileException {
        if (handler == null || handler.landmarks() < 2) {
            return;
        }

        Collector collector = Collector.instance();

        int offset = 0;

        for (int l = 0; l < handler.landmarks(); l++) {
            Node landmarkNode = collector.createNode(node, handler.getLandmarkName(l), Type.LANDMARK, node.getLine());
            HitProbe probe = collector.createHitProbe(c.getName(), landmarkNode.getId());

            if (l == 0) {
                offset = probe.getLocalId();
            }
        }

        int landmark = handlers.size();
        handlers.add(handler);
        String toInject = "{ " + InstrumentationPass.HIT_VECTOR_NAME + "[" + offset + "+" + LANDMARK_VECTOR_NAME + "["
                + landmark + "].handle(" + variable + ")] = true; }";
        if (isReturn) {
            b.insertAfter(toInject);
        } else if (b instanceof CtConstructor) {
            ((CtConstructor)b).insertBeforeBody(toInject);
        } else {
            b.insertBefore(toInject);
        }

    }

    private LandmarkHandler getLandmarkHandler(CtClass paramType, Object[] annotations, boolean isReturn)
            throws NotFoundException {
        if (!dispatcher.overrideAnnotations()) {
            LandmarkHandler handler = processAnnotations(annotations);
            if (handler != null) {
                return handler;
            }
        }
        return dispatcher.getHandler(paramType.getName(), isReturn);
    }

    private LandmarkHandler processAnnotations(Object[] annotations) {
        if (annotations == null) {
            return null;
        }

        for (Object o : annotations) {
            try {
                Method m = o.getClass().getDeclaredMethod("type");
                if (m.invoke(o) != LandmarkAnnotation.class) {
                    continue;
                }
                m = o.getClass().getDeclaredMethod("handler");
                Object ret = m.invoke(o);
                if (ret instanceof Class<?>) {
                    LandmarkHandler handler = (LandmarkHandler) ((Class<?>) ret).newInstance();
                    return handler;
                }
            } catch (Exception e) {
            }
        }
        return null;
    }
}
